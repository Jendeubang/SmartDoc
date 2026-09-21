package com.javaee.fileservice.service.impl;

import com.javaee.fileservice.client.DocumentServiceClient;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.security.BucketPermissionService;
import com.javaee.fileservice.security.FileUploadValidator;
import com.javaee.fileservice.security.MalwareScanner;
import com.javaee.fileservice.service.FileMetadataService;
import com.javaee.fileservice.service.FileService;
import com.javaee.fileservice.service.ReliableFileUploader;
import com.javaee.fileservice.storage.FileObjectStore;
import com.javaee.fileservice.util.DocumentTextExtractor;
import com.javaee.fileservice.util.FileUtils;
import com.javaee.common.config.security.TenantContext;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.StatObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 文件服务实现类
 */
@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private FileStorageConfig fileStorageConfig;

    @Autowired
    private FileMetadataService fileMetadataService;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private BucketPermissionService bucketPermissionService;

    @Autowired
    private FileUploadValidator fileUploadValidator;

    @Autowired
    private MalwareScanner malwareScanner;

    @Autowired
    private ReliableFileUploader reliableFileUploader;

    @Autowired
    private FileObjectStore fileObjectStore;

    @Autowired(required = false)
    private DocumentServiceClient documentServiceClient;

    // 用于存储分片上传的临时文件
    private final ConcurrentMap<String, ConcurrentMap<Integer, File>> chunkMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> chunkTotalMap = new ConcurrentHashMap<>();

    private static final int MAX_CHUNKS = 10_000;

    @Override
    public String upload(MultipartFile file) {
        try {
            assertMinioBucketAccess();
            ReliableFileUploader.UploadResult result = reliableFileUploader.upload(
                    file, currentUserId(), currentOrganizationId());
            processFileAfterUpload(result);
            return result.fileId();
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String[] uploadMultiple(MultipartFile[] files) {
        validateBatch(files);
        String[] fileIds = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            fileIds[i] = upload(files[i]);
        }
        return fileIds;
    }

    private void validateBatch(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("至少选择一个文件");
        }
        int maxBatchFiles = fileStorageConfig.getMaxBatchFiles();
        if (maxBatchFiles <= 0 || files.length > maxBatchFiles) {
            throw new IllegalArgumentException("批量文件数量不能超过 " + maxBatchFiles + " 个");
        }
        if (fileStorageConfig.getMaxBatchConcurrency() <= 0) {
            throw new IllegalArgumentException("批量并发配置不合法");
        }
        long maxFileBytes = toBytes(fileStorageConfig.getMaxSize(), "单文件大小配置");
        long maxBatchBytes = toBytes(fileStorageConfig.getMaxBatchTotalSizeMb(), "批量总大小配置");
        long totalBytes = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("批量上传中不能包含空文件");
            }
            if (file.getSize() > maxFileBytes) {
                throw new IllegalArgumentException("文件超过 " + fileStorageConfig.getMaxSize() + "MB 上传限制");
            }
            if (totalBytes > maxBatchBytes - file.getSize()) {
                throw new IllegalArgumentException("批量文件总大小不能超过 "
                        + fileStorageConfig.getMaxBatchTotalSizeMb() + "MB");
            }
            totalBytes += file.getSize();
        }
    }

    private long toBytes(long megabytes, String settingName) {
        if (megabytes <= 0 || megabytes > Long.MAX_VALUE / (1024L * 1024L)) {
            throw new IllegalArgumentException(settingName + "不合法");
        }
        return megabytes * 1024L * 1024L;
    }

    @Override
    public void uploadChunk(MultipartFile chunk, String fileId, int chunkIndex, int totalChunks) {
        validateChunkRequest(chunk, fileId, chunkIndex, totalChunks);
        String sessionKey = chunkSessionKey(fileId);
        try {
            assertMinioBucketAccess();
            Integer previousTotal = chunkTotalMap.putIfAbsent(sessionKey, totalChunks);
            if (previousTotal != null && previousTotal != totalChunks) {
                throw new IllegalArgumentException("同一文件的分片总数不能变化");
            }
            chunkMap.computeIfAbsent(sessionKey, k -> new ConcurrentHashMap<>());
            ConcurrentMap<Integer, File> chunks = chunkMap.get(sessionKey);

            File chunkFile = File.createTempFile("chunk_" + fileId + "_", null);
            try {
                chunk.transferTo(chunkFile);
                if (chunks.putIfAbsent(chunkIndex, chunkFile) != null) {
                    throw new IllegalArgumentException("分片已经上传，不能重复覆盖");
                }
            } catch (Exception e) {
                chunkFile.delete();
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String mergeChunk(String fileId, String fileName) {
        validateChunkFileId(fileId);
        String validFileName = fileUploadValidator.validateFileName(fileName);
        String sessionKey = chunkSessionKey(fileId);
        ConcurrentMap<Integer, File> chunks = chunkMap.get(sessionKey);
        try {
            assertMinioBucketAccess();
            Integer totalChunks = chunkTotalMap.get(sessionKey);
            if (chunks == null || totalChunks == null) {
                throw new RuntimeException("没有找到分片文件");
            }
            for (int i = 0; i < totalChunks; i++) {
                if (!chunks.containsKey(i) || chunks.get(i) == null) {
                    throw new IllegalArgumentException("缺少第 " + i + " 个分片");
                }
            }
            long mergedSize = chunks.values().stream().mapToLong(File::length).sum();
            if (mergedSize > fileStorageConfig.getMaxSize() * 1024L * 1024L) {
                throw new IllegalArgumentException("合并文件超过 " + fileStorageConfig.getMaxSize() + "MB 上传限制");
            }
            Path mergedPath = Files.createTempFile("smartdoc-merged-", ".upload");
            try {
                try (OutputStream output = Files.newOutputStream(mergedPath)) {
                    for (int i = 0; i < totalChunks; i++) {
                        Files.copy(chunks.get(i).toPath(), output);
                    }
                }
                ReliableFileUploader.UploadResult result = reliableFileUploader.uploadFile(
                        mergedPath, validFileName, FileUtils.getContentType(validFileName),
                        currentUserId(), currentOrganizationId());
                processFileAfterUpload(result);
                return result.fileId();
            } finally {
                Files.deleteIfExists(mergedPath);
            }
        } catch (Exception e) {
            throw new RuntimeException("分片合并失败: " + e.getMessage(), e);
        } finally {
            cleanupChunkSession(sessionKey, chunks);
        }
    }

    private void validateChunkRequest(MultipartFile chunk, String fileId, int chunkIndex, int totalChunks) {
        validateChunkFileId(fileId);
        if (chunk == null || chunk.isEmpty()) {
            throw new IllegalArgumentException("分片不能为空");
        }
        if (totalChunks <= 0 || totalChunks > MAX_CHUNKS) {
            throw new IllegalArgumentException("分片总数不合法");
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("分片序号超出范围");
        }
        if (chunk.getSize() > fileStorageConfig.getMaxSize() * 1024L * 1024L) {
            throw new IllegalArgumentException("分片超过上传大小限制");
        }
    }

    private void validateChunkFileId(String fileId) {
        if (fileId == null || !fileId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("分片文件标识不合法");
        }
    }

    private String chunkSessionKey(String fileId) {
        return currentUserId() + "|" + currentOrganizationId() + "|" + fileId;
    }

    private void cleanupChunkSession(String sessionKey, ConcurrentMap<Integer, File> chunks) {
        if (chunks != null) {
            chunks.values().forEach(file -> {
                if (file != null) {
                    file.delete();
                }
            });
        }
        chunkMap.remove(sessionKey, chunks);
        chunkTotalMap.remove(sessionKey);
    }

    @Override
    public byte[] download(String fileId) {
        try {
            assertMinioBucketAccess();
            // 尝试从数据库获取文件元数据
            com.javaee.fileservice.entity.FileMetadata fileMetadata = null;
            String storageFileName = null;

            try {
                fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null) {
                    storageFileName = fileMetadata.getObjectKey();
                }
            } catch (Exception e) {
                // 数据库不可用时，尝试不同的文件扩展名
                System.out.println("数据库不可用，尝试不同的文件扩展名: " + e.getMessage());
                // 尝试常见的文件扩展名
                String[] extensions = {"", ".docx", ".pdf", ".txt", ".jpg", ".png", ".jpeg"};
                for (String ext : extensions) {
                    try {
                        String tempFileName = fileId + ext;
                        try (InputStream inputStream = minioClient.getObject(
                                GetObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(tempFileName)
                                        .build()
                        )) {
                            return FileUtils.toByteArray(inputStream);
                        }
                    } catch (Exception ex) {
                        // 忽略错误，尝试下一个扩展名
                        System.out.println("尝试扩展名失败: " + ext);
                    }
                }
                // 如果所有扩展名都失败，抛出异常
                throw new RuntimeException("文件不存在: " + fileId);
            }

            // 如果还是没有storageFileName，使用fileId作为默认值
            if (storageFileName == null) {
                storageFileName = fileId;
            }

            // 根据存储类型下载文件
            if ("local".equals(fileStorageConfig.getStorageType())) {
                // 本地存储
                Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                return Files.readAllBytes(storagePath);
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                // MinIO存储
                try (InputStream inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(fileStorageConfig.getBucketName())
                                .object(storageFileName)
                                .build()
                )) {
                    return FileUtils.toByteArray(inputStream);
                }
            }

            throw new RuntimeException("不支持的存储类型: " + fileStorageConfig.getStorageType());
        } catch (Exception e) {
            throw new RuntimeException("文件下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String fileId) {
        try {
            assertMinioBucketAccess();
            // 尝试从数据库获取文件元数据
            com.javaee.fileservice.entity.FileMetadata fileMetadata = null;
            String storageFileName = null;

            try {
                fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null) {
                    storageFileName = fileMetadata.getObjectKey();
                    System.out.println("从数据库获取到文件元数据，存储文件名: " + storageFileName);
                } else {
                    System.out.println("数据库中未找到文件元数据: " + fileId);
                }
            } catch (Exception e) {
                // 数据库不可用时，尝试不同的文件扩展名
                System.out.println("数据库不可用，尝试不同的文件扩展名: " + e.getMessage());
                // 尝试常见的文件扩展名
                String[] extensions = {".docx", ".pdf", ".txt", ".jpg", ".png", ".jpeg", ""};
                boolean deleted = false;
                for (String ext : extensions) {
                    try {
                        String tempFileName = fileId + ext;
                        System.out.println("尝试删除MinIO文件: " + tempFileName);
                        System.out.println("MinIO配置: bucket=" + fileStorageConfig.getBucketName());
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(tempFileName)
                                        .build()
                        );
                        deleted = true;
                        System.out.println("成功删除文件: " + tempFileName);
                        // 继续尝试其他扩展名，确保删除所有可能的文件
                    } catch (Exception ex) {
                        // 忽略错误，尝试下一个扩展名
                        System.out.println("尝试扩展名失败: " + ext + ", 错误: " + ex.getMessage());
                    }
                }
                if (deleted) {
                    return;
                } else {
                    throw new RuntimeException("文件不存在: " + fileId);
                }
            }

            // 如果有存储文件名，直接删除
            if (storageFileName != null) {
                System.out.println("使用存储文件名删除文件: " + storageFileName);
                if ("minio".equals(fileStorageConfig.getStorageType())) {
                    try {
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(storageFileName)
                                        .build()
                        );
                        System.out.println("成功删除文件: " + storageFileName);
                    } catch (Exception e) {
                        System.out.println("删除文件失败: " + e.getMessage());
                        throw new RuntimeException("文件删除失败: " + e.getMessage());
                    }
                } else if ("local".equals(fileStorageConfig.getStorageType())) {
                    try {
                        Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                        Files.deleteIfExists(storagePath);
                        System.out.println("成功删除本地文件: " + storagePath);
                    } catch (Exception e) {
                        System.out.println("删除本地文件失败: " + e.getMessage());
                        throw new RuntimeException("文件删除失败: " + e.getMessage());
                    }
                } else {
                    throw new RuntimeException("不支持的存储类型: " + fileStorageConfig.getStorageType());
                }
            } else {
                // 如果没有存储文件名，尝试使用fileId加不同扩展名删除
                System.out.println("没有存储文件名，尝试使用fileId加扩展名删除");
                String[] extensions = {".txt", ".docx", ".pdf", ".jpg", ".png", ".jpeg", ""};
                boolean deleted = false;
                for (String ext : extensions) {
                    String tempFileName = fileId + ext;
                    try {
                        if ("minio".equals(fileStorageConfig.getStorageType())) {
                            minioClient.removeObject(
                                    RemoveObjectArgs.builder()
                                            .bucket(fileStorageConfig.getBucketName())
                                            .object(tempFileName)
                                            .build()
                            );
                            System.out.println("成功删除MinIO文件: " + tempFileName);
                        } else if ("local".equals(fileStorageConfig.getStorageType())) {
                            Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), tempFileName);
                            Files.deleteIfExists(storagePath);
                            System.out.println("成功删除本地文件: " + storagePath);
                        }
                        deleted = true;
                        break; // 删除成功后退出循环
                    } catch (Exception ex) {
                        // 忽略错误，尝试下一个扩展名
                        System.out.println("尝试删除失败: " + tempFileName + ", 错误: " + ex.getMessage());
                    }
                }
                if (!deleted) {
                    throw new RuntimeException("文件不存在: " + fileId);
                }
            }

            // 删除文件元数据
            try {
                fileMetadataService.deleteMetadata(fileId);
            } catch (Exception e) {
                // 数据库不可用时，忽略错误
                System.out.println("数据库不可用，跳过元数据删除: " + e.getMessage());
            }
        } catch (Exception e) {
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void rename(String fileId, String newName) {
        try {
            assertMinioBucketAccess();
            // 尝试从数据库获取文件元数据
            com.javaee.fileservice.entity.FileMetadata fileMetadata = null;
            String storageFileName = null;

            try {
                fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null) {
                    storageFileName = fileMetadata.getObjectKey();
                }
            } catch (Exception e) {
                // 数据库不可用时，尝试不同的文件扩展名
                System.out.println("数据库不可用，尝试不同的文件扩展名: " + e.getMessage());
            }

            // 如果没有找到存储文件名，使用fileId作为默认值（去除扩展名）
            if (storageFileName == null) {
                // 去除fileId中的扩展名
                int dotIndex = fileId.lastIndexOf('.');
                if (dotIndex > 0) {
                    storageFileName = fileId.substring(0, dotIndex);
                } else {
                    storageFileName = fileId;
                }
            }

            String oldFileName = storageFileName;
            String fileExtension = FileUtils.getFileExtension(newName);
            // 新存储文件名使用fileId（去除扩展名）加上新的文件名
            int dotIndex = fileId.lastIndexOf('.');
            String fileIdWithoutExt = fileId;
            if (dotIndex > 0) {
                fileIdWithoutExt = fileId.substring(0, dotIndex);
            }
            // 直接使用新文件名作为存储文件名
            String newStorageFileName = newName;

            // 根据存储类型重命名文件
            if ("local".equals(fileStorageConfig.getStorageType())) {
                // 本地存储
                Path oldPath = Paths.get(fileStorageConfig.getLocalPath(), oldFileName);
                Path newPath = Paths.get(fileStorageConfig.getLocalPath(), newStorageFileName);
                Files.move(oldPath, newPath);
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                // MinIO存储（先复制再删除）
                try {
                    // 尝试不同的文件扩展名找到原文件
                    String[] extensions = {"", ".txt", ".docx", ".pdf", ".jpg", ".png", ".jpeg"};
                    String foundOldFileName = null;

                    for (String ext : extensions) {
                        try {
                            String tempOldFileName = oldFileName + ext;
                            // 检查文件是否存在
                            minioClient.statObject(
                                    StatObjectArgs.builder()
                                            .bucket(fileStorageConfig.getBucketName())
                                            .object(tempOldFileName)
                                            .build()
                            );
                            foundOldFileName = tempOldFileName;
                            break;
                        } catch (Exception ex) {
                            // 忽略错误，尝试下一个扩展名
                        }
                    }

                    if (foundOldFileName != null) {
                        // 复制文件
                        minioClient.copyObject(
                                CopyObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(newStorageFileName)
                                        .source(
                                                CopySource.builder()
                                                        .bucket(fileStorageConfig.getBucketName())
                                                        .object(foundOldFileName)
                                                        .build()
                                        )
                                        .build()
                        );
                        // 删除原文件
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(foundOldFileName)
                                        .build()
                        );
                        System.out.println("MinIO文件重命名成功: " + foundOldFileName + " -> " + newStorageFileName);
                    } else {
                        System.out.println("MinIO中未找到原文件: " + oldFileName);
                    }
                } catch (Exception e) {
                    // 如果复制失败，尝试直接使用新名称上传（简化处理）
                    System.out.println("MinIO复制失败，尝试直接使用新名称: " + e.getMessage());
                }
            }

            // 更新文件元数据
            try {
                if (fileMetadata != null) {
                    fileMetadata.setFileName(newName);
                    fileMetadata.setOriginalFileName(newName);
                    fileMetadata.setObjectKey(newStorageFileName);
                    fileMetadataService.updateMetadata(fileMetadata);
                }
            } catch (Exception e) {
                // 数据库不可用时，忽略错误
                System.out.println("数据库不可用，跳过元数据更新: " + e.getMessage());
            }
        } catch (Exception e) {
            throw new RuntimeException("文件重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void move(String fileId, String targetPath) {
        try {
            assertMinioBucketAccess();
            // 尝试从数据库获取文件元数据
            com.javaee.fileservice.entity.FileMetadata fileMetadata = null;
            String storageFileName = null;

            try {
                fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null) {
                    storageFileName = fileMetadata.getObjectKey();
                    System.out.println("从数据库获取到文件元数据，存储文件名: " + storageFileName);
                } else {
                    System.out.println("数据库中未找到文件元数据: " + fileId);
                }
            } catch (Exception e) {
                // 数据库不可用时，使用fileId作为默认值
                System.out.println("数据库不可用: " + e.getMessage());
                storageFileName = fileId;
            }

            // 如果没有存储文件名，使用fileId作为默认值
            if (storageFileName == null) {
                storageFileName = fileId;
            }

            // 根据存储类型移动文件
            if ("local".equals(fileStorageConfig.getStorageType())) {
                // 本地存储
                Path oldPath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                Path newPath = Paths.get(targetPath, storageFileName);
                Files.createDirectories(newPath.getParent());
                Files.move(oldPath, newPath);
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                // MinIO存储（先复制再删除）
                try {
                    // 尝试不同的文件扩展名找到原文件
                    String[] extensions = {"", ".txt", ".docx", ".pdf", ".jpg", ".png", ".jpeg"};
                    String foundStorageFileName = null;

                    for (String ext : extensions) {
                        try {
                            String tempFileName = storageFileName + ext;
                            // 检查文件是否存在
                            minioClient.statObject(
                                    StatObjectArgs.builder()
                                            .bucket(fileStorageConfig.getBucketName())
                                            .object(tempFileName)
                                            .build()
                            );
                            foundStorageFileName = tempFileName;
                            break;
                        } catch (Exception ex) {
                            // 忽略错误，尝试下一个扩展名
                        }
                    }

                    if (foundStorageFileName != null) {
                        // 构建新的存储路径
                        String newStoragePath = targetPath + "/" + foundStorageFileName;
                        // 移除开头的斜杠
                        if (newStoragePath.startsWith("/")) {
                            newStoragePath = newStoragePath.substring(1);
                        }

                        // 复制文件
                        minioClient.copyObject(
                                CopyObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(newStoragePath)
                                        .source(
                                                CopySource.builder()
                                                        .bucket(fileStorageConfig.getBucketName())
                                                        .object(foundStorageFileName)
                                                        .build()
                                        )
                                        .build()
                        );
                        // 删除原文件
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(foundStorageFileName)
                                        .build()
                        );
                        System.out.println("MinIO文件移动成功: " + foundStorageFileName + " -> " + newStoragePath);
                    } else {
                        throw new RuntimeException("文件不存在: " + storageFileName);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("MinIO文件移动失败: " + e.getMessage(), e);
                }
            }

            // 更新文件元数据（如果数据库可用）
            try {
                if (fileMetadata != null) {
                    fileMetadata.setFilePath(targetPath);
                    fileMetadataService.updateMetadata(fileMetadata);
                }
            } catch (Exception e) {
                // 数据库不可用时，忽略错误
                System.out.println("更新文件元数据失败: " + e.getMessage());
            }
        } catch (Exception e) {
            throw new RuntimeException("文件移动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String copy(String fileId, String targetPath) {
        try {
            assertMinioBucketAccess();
            // 尝试从数据库获取文件元数据
            com.javaee.fileservice.entity.FileMetadata fileMetadata = null;
            String storageFileName = null;
            String fileName = "copy_" + fileId + ".txt";

            try {
                fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null) {
                    storageFileName = fileMetadata.getObjectKey();
                    fileName = fileMetadata.getFileName();
                    System.out.println("从数据库获取到文件元数据，存储文件名: " + storageFileName);
                } else {
                    System.out.println("数据库中未找到文件元数据: " + fileId);
                }
            } catch (Exception e) {
                // 数据库不可用时，使用fileId作为默认值
                System.out.println("数据库不可用: " + e.getMessage());
                storageFileName = fileId;
            }

            // 如果没有存储文件名，使用fileId作为默认值
            if (storageFileName == null) {
                storageFileName = fileId;
            }

            // 生成新的文件ID
            String newFileId = UUID.randomUUID().toString();
            String newFileExtension = FileUtils.getFileExtension(fileName);
            String newStorageFileName = newFileId + (newFileExtension != null ? "." + newFileExtension : ".txt");

            // 根据存储类型复制文件
            if ("local".equals(fileStorageConfig.getStorageType())) {
                // 本地存储
                Path oldPath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                Path newPath = Paths.get(targetPath, newStorageFileName);
                Files.createDirectories(newPath.getParent());
                Files.copy(oldPath, newPath);
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                // MinIO存储
                try {
                    // 尝试不同的文件扩展名找到原文件
                    String[] extensions = {"", ".txt", ".docx", ".pdf", ".jpg", ".png", ".jpeg"};
                    String foundStorageFileName = null;

                    for (String ext : extensions) {
                        try {
                            String tempFileName = storageFileName + ext;
                            // 检查文件是否存在
                            minioClient.statObject(
                                    StatObjectArgs.builder()
                                            .bucket(fileStorageConfig.getBucketName())
                                            .object(tempFileName)
                                            .build()
                            );
                            foundStorageFileName = tempFileName;
                            break;
                        } catch (Exception ex) {
                            // 忽略错误，尝试下一个扩展名
                        }
                    }

                    if (foundStorageFileName != null) {
                        // 构建新的存储路径
                        String newStoragePath = targetPath + "/" + newStorageFileName;
                        // 移除开头的斜杠
                        if (newStoragePath.startsWith("/")) {
                            newStoragePath = newStoragePath.substring(1);
                        }

                        // 复制文件
                        minioClient.copyObject(
                                CopyObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(newStoragePath)
                                        .source(
                                                CopySource.builder()
                                                        .bucket(fileStorageConfig.getBucketName())
                                                        .object(foundStorageFileName)
                                                        .build()
                                        )
                                        .build()
                        );
                        System.out.println("MinIO文件复制成功: " + foundStorageFileName + " -> " + newStoragePath);
                    } else {
                        throw new RuntimeException("文件不存在: " + storageFileName);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("MinIO文件复制失败: " + e.getMessage(), e);
                }
            }

            // 保存新文件的元数据（如果数据库可用）
            try {
                if (fileMetadata != null) {
                    com.javaee.fileservice.entity.FileMetadata newFileMetadata = new com.javaee.fileservice.entity.FileMetadata();
                    newFileMetadata.setFileId(newFileId);
                    newFileMetadata.setFileName(fileName);
                    newFileMetadata.setOriginalFileName(fileName);
                    newFileMetadata.setFilePath(targetPath);
                    newFileMetadata.setFileType(fileMetadata.getFileType());
                    newFileMetadata.setFileSize(fileMetadata.getFileSize());
                    newFileMetadata.setStorageType(fileMetadata.getStorageType());
                    newFileMetadata.setBucketName(fileMetadata.getBucketName());
                    newFileMetadata.setObjectKey(newStorageFileName);
                    newFileMetadata.setCreateBy(currentUserId());
                    newFileMetadata.setOrganizationId(fileMetadata.getOrganizationId());
                    fileMetadataService.saveMetadata(newFileMetadata);
                }
            } catch (Exception e) {
                // 数据库不可用时，忽略错误
                System.out.println("保存新文件元数据失败: " + e.getMessage());
            }

            return newFileId;
        } catch (Exception e) {
            throw new RuntimeException("文件复制失败: " + e.getMessage(), e);
        }
    }

    /**
     * [NEW] 上传后处理：解析文档文本 → 上传 .txt 到 MinIO → 调用 document-service 创建文档记录
     */
    private void processFileAfterUpload(ReliableFileUploader.UploadResult result) {
        try {
            String fileName = result.fileName();
            String text;
            if (result.bytes() != null) {
                text = DocumentTextExtractor.extractText(result.bytes(), fileName);
            } else {
                try (InputStream input = fileObjectStore.open(result.objectKey())) {
                    text = DocumentTextExtractor.extractText(input, fileName);
                }
            }
            if (text.isEmpty()) {
                System.out.println("文件内容为空或格式不支持，跳过文档创建: fileId=" + result.fileId());
                return;
            }

            // 上传纯文本到 MinIO
            String bucketName = fileStorageConfig.getBucketName();
            ensureBucketExists(bucketName);
            String txtObjectKey = "document-content/" + result.fileId() + ".txt";
            byte[] textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(txtObjectKey)
                            .stream(new ByteArrayInputStream(textBytes), textBytes.length, -1)
                            .contentType("text/plain; charset=UTF-8")
                            .build()
            );
            System.out.println("文本已上传到 MinIO: bucket=" + bucketName + ", key=" + txtObjectKey);

            // 调用 document-service 创建文档记录
            if (documentServiceClient != null) {
                String title = fileName != null ? fileName : result.fileId();
                java.util.Map<String, Object> docRequest = new java.util.LinkedHashMap<>();
                docRequest.put("title", title);
                docRequest.put("fileId", result.fileId());
                docRequest.put("bucketName", bucketName);
                docRequest.put("objectName", txtObjectKey);
                docRequest.put("category", "default");
                java.util.Map<String, Object> response = documentServiceClient.createDocument(docRequest);
                System.out.println("文档创建成功: " + response);
            }
        } catch (Exception e) {
            System.out.println("上传后处理失败（文件已正常上传，不影响主流程）: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void ensureBucketExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }

    private void assertMinioBucketAccess() {
        if ("minio".equals(fileStorageConfig.getStorageType())) {
            bucketPermissionService.assertCanAccess(fileStorageConfig.getBucketName());
        }
    }
    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getPrincipal() != null) {
            return authentication.getPrincipal().toString();
        }
        throw new SecurityException("用户未认证，请先登录");
    }

    private String currentOrganizationId() {
        return TenantContext.get();
    }
}
