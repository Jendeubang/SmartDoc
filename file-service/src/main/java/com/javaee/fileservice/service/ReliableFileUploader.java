package com.javaee.fileservice.service;

import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.security.FileUploadValidator;
import com.javaee.fileservice.security.MalwareScanner;
import com.javaee.fileservice.storage.FileObjectStore;
import com.javaee.fileservice.util.FileUtils;
import com.javaee.fileservice.util.Md5Utils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Coordinates validated, compensating file uploads and duplicate replacement. */
@Service
public class ReliableFileUploader {

    private final FileStorageConfig storageConfig;
    private final FileMetadataService metadataService;
    private final FileUploadValidator validator;
    private final MalwareScanner malwareScanner;
    private final FileObjectStore objectStore;
    private final ConcurrentMap<String, Object> duplicateLocks = new ConcurrentHashMap<>();

    public ReliableFileUploader(FileStorageConfig storageConfig,
                                FileMetadataService metadataService,
                                FileUploadValidator validator,
                                MalwareScanner malwareScanner,
                                FileObjectStore objectStore) {
        this.storageConfig = storageConfig;
        this.metadataService = metadataService;
        this.validator = validator;
        this.malwareScanner = malwareScanner;
        this.objectStore = objectStore;
    }

    public UploadResult upload(MultipartFile file, String userId, String organizationId) {
        validator.validate(file);
        return uploadInternal(file.getOriginalFilename(), file.getContentType(), file.getSize(),
                () -> file.getInputStream(), userId, organizationId, null);
    }

    public UploadResult uploadBytes(String fileName, String contentType, byte[] bytes,
                                    String userId, String organizationId) {
        String validName = validator.validateFileName(fileName);
        validator.validateBytes(validName, bytes);
        return uploadInternal(validName, contentType, bytes.length, () -> new ByteArrayInputStream(bytes),
                userId, organizationId, bytes);
    }

    /** Uploads a file-backed source without retaining the complete payload in heap memory. */
    public UploadResult uploadFile(Path path, String fileName, String contentType,
                                   String userId, String organizationId) {
        if (path == null) {
            throw new IllegalArgumentException("上传文件路径不能为空");
        }
        try {
            long size = Files.size(path);
            return uploadInternal(fileName, contentType, size,
                    () -> Files.newInputStream(path), userId, organizationId, null);
        } catch (IOException e) {
            throw new IllegalStateException("读取临时文件失败", e);
        }
    }

    private UploadResult uploadInternal(String fileName, String contentType, long size,
                                        UploadSource source, String userId, String organizationId,
                                        byte[] retainedBytes) {
        String validName = validator.validateFileName(fileName);
        if (size <= 0) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String resolvedContentType = contentType == null || contentType.isBlank()
                ? FileUtils.getContentType(validName) : contentType;
        String md5;
        try (InputStream input = source.open()) {
            md5 = Md5Utils.calculateInputStreamMd5(input);
        } catch (IOException e) {
            throw new IllegalStateException("计算文件摘要失败", e);
        }
        String bucket = storageConfig.getBucketName();
        String lockKey = bucket + "|" + organizationId + "|" + userId + "|" + md5;
        Object lock = duplicateLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            try {
                try (InputStream input = source.open()) {
                    malwareScanner.scan(input);
                } catch (IOException e) {
                    throw new IllegalStateException("文件读取失败", e);
                }
                return persistWithCompensation(validName, resolvedContentType, size, source, retainedBytes,
                        md5, userId, organizationId, bucket);
            } finally {
                duplicateLocks.remove(lockKey, lock);
            }
        }
    }

    private UploadResult persistWithCompensation(String fileName, String contentType, long size,
                                                 UploadSource source, byte[] retainedBytes,
                                                 String md5, String userId, String organizationId,
                                                 String bucket) {
        FileMetadata previous = metadataService.findReadyByMd5(md5, userId, organizationId);
        String fileId = UUID.randomUUID().toString();
        String extension = FileUtils.getFileExtension(fileName);
        String suffix = extension == null ? "" : "." + extension;
        String formalObjectKey = fileId + suffix;
        String temporaryObjectKey = ".uploading/" + safePathSegment(userId) + "/" + fileId + suffix;
        FileMetadata pending = buildMetadata(fileId, fileName, contentType, size, md5,
                userId, organizationId, bucket, temporaryObjectKey, "PENDING");
        boolean temporaryAttempted = false;
        boolean metadataSaved = false;
        boolean formalPromoted = false;
        try {
            temporaryAttempted = true;
            if (retainedBytes != null) {
                objectStore.put(temporaryObjectKey, retainedBytes, contentType);
            } else {
                try (InputStream input = source.open()) {
                    objectStore.put(temporaryObjectKey, input, size, contentType);
                }
            }
            metadataService.saveMetadata(pending);
            metadataSaved = true;

            objectStore.promote(temporaryObjectKey, formalObjectKey);
            formalPromoted = true;
            pending.setObjectKey(formalObjectKey);
            pending.setStatus("READY");
            metadataService.updateMetadata(pending);

            replacePrevious(previous);
            return new UploadResult(fileId, fileName, contentType, retainedBytes, md5, formalObjectKey);
        } catch (Exception failure) {
            if (metadataSaved) {
                pending.setStatus("FAILED");
                try {
                    metadataService.updateMetadata(pending);
                } catch (Exception compensationFailure) {
                    System.err.println("标记失败上传状态失败: " + compensationFailure.getMessage());
                }
            }
            if (temporaryAttempted) {
                try {
                    objectStore.delete(formalPromoted ? formalObjectKey : temporaryObjectKey);
                } catch (Exception cleanupFailure) {
                    System.err.println("清理失败上传对象失败: " + cleanupFailure.getMessage());
                }
            }
            throw new IllegalStateException("文件上传失败: " + failure.getMessage(), failure);
        }
    }

    private void replacePrevious(FileMetadata previous) {
        if (previous == null || previous.getFileId() == null) {
            return;
        }
        try {
            objectStore.delete(previous.getObjectKey());
        } catch (Exception e) {
            System.err.println("删除旧文件对象失败，新文件仍保持 READY: " + e.getMessage());
        }
        try {
            metadataService.deleteMetadata(previous.getFileId());
        } catch (Exception e) {
            System.err.println("删除旧文件元数据失败，新文件仍保持 READY: " + e.getMessage());
        }
    }

    private FileMetadata buildMetadata(String fileId, String fileName, String contentType, long size,
                                       String md5, String userId, String organizationId, String bucket,
                                       String objectKey, String status) {
        FileMetadata metadata = new FileMetadata();
        metadata.setFileId(fileId);
        metadata.setFileName(fileName);
        metadata.setOriginalFileName(fileName);
        metadata.setFilePath("minio".equalsIgnoreCase(storageConfig.getStorageType())
                ? "minio:" + bucket : storageConfig.getLocalPath());
        metadata.setFileType(contentType);
        metadata.setFileSize(size);
        metadata.setMd5(md5);
        metadata.setStorageType(storageConfig.getStorageType());
        metadata.setBucketName(bucket);
        metadata.setOrganizationId(organizationId);
        metadata.setObjectKey(objectKey);
        metadata.setCreateBy(userId);
        metadata.setStatus(status);
        return metadata;
    }

    private String safePathSegment(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("用户标识不合法");
        }
        return value;
    }

    public record UploadResult(String fileId, String fileName, String contentType, byte[] bytes, String md5,
                               String objectKey) {
        public UploadResult(String fileId, String fileName, String contentType, byte[] bytes, String md5) {
            this(fileId, fileName, contentType, bytes, md5, null);
        }
    }

    @FunctionalInterface
    private interface UploadSource {
        InputStream open() throws IOException;
    }
}
