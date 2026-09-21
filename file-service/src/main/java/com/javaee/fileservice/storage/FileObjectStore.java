package com.javaee.fileservice.storage;

import com.javaee.fileservice.config.FileStorageConfig;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Encapsulates object writes so upload callers cannot expose a half-written
 * formal object. Every operation is scoped to the current user's storage bucket.
 */
@Component
public class FileObjectStore {

    private final FileStorageConfig storageConfig;
    private final MinioClient minioClient;

    public FileObjectStore(FileStorageConfig storageConfig, MinioClient minioClient) {
        this.storageConfig = storageConfig;
        this.minioClient = minioClient;
    }

    public void put(String objectKey, byte[] bytes, String contentType) {
        put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    /** Writes an object without materializing the whole payload in the caller. */
    public void put(String objectKey, InputStream input, long size, String contentType) {
        if ("local".equalsIgnoreCase(storageConfig.getStorageType())) {
            writeLocal(objectKey, input);
            return;
        }
        if ("minio".equalsIgnoreCase(storageConfig.getStorageType())) {
            String bucket = storageConfig.getBucketName();
            ensureBucket(bucket);
            try {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(input, size, -1)
                        .contentType(contentType)
                        .build());
            } catch (Exception e) {
                throw new IllegalStateException("对象临时上传失败", e);
            }
            return;
        }
        throw new IllegalStateException("不支持的存储类型: " + storageConfig.getStorageType());
    }

    public void promote(String temporaryObjectKey, String formalObjectKey) {
        if ("local".equalsIgnoreCase(storageConfig.getStorageType())) {
            Path source = safeLocalPath(temporaryObjectKey);
            Path target = safeLocalPath(formalObjectKey);
            try {
                Files.createDirectories(target.getParent());
                try {
                    Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new IllegalStateException("正式对象提升失败", e);
            }
            return;
        }
        if ("minio".equalsIgnoreCase(storageConfig.getStorageType())) {
            String bucket = storageConfig.getBucketName();
            boolean copied = false;
            try {
                minioClient.copyObject(CopyObjectArgs.builder()
                        .bucket(bucket)
                        .object(formalObjectKey)
                        .source(CopySource.builder().bucket(bucket).object(temporaryObjectKey).build())
                        .build());
                copied = true;
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(temporaryObjectKey)
                        .build());
            } catch (Exception e) {
                if (copied) {
                    try {
                        minioClient.removeObject(RemoveObjectArgs.builder()
                                .bucket(bucket)
                                .object(formalObjectKey)
                                .build());
                    } catch (Exception cleanupFailure) {
                        e.addSuppressed(cleanupFailure);
                    }
                }
                throw new IllegalStateException("正式对象提升失败", e);
            }
            return;
        }
        throw new IllegalStateException("不支持的存储类型: " + storageConfig.getStorageType());
    }

    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        if ("local".equalsIgnoreCase(storageConfig.getStorageType())) {
            try {
                Files.deleteIfExists(safeLocalPath(objectKey));
            } catch (Exception e) {
                throw new IllegalStateException("删除对象失败", e);
            }
            return;
        }
        if ("minio".equalsIgnoreCase(storageConfig.getStorageType())) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(storageConfig.getBucketName())
                        .object(objectKey)
                        .build());
            } catch (Exception e) {
                throw new IllegalStateException("删除对象失败", e);
            }
            return;
        }
        throw new IllegalStateException("不支持的存储类型: " + storageConfig.getStorageType());
    }

    /** Opens a stored object for streaming reads. The caller owns and must close the stream. */
    public InputStream open(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        if ("local".equalsIgnoreCase(storageConfig.getStorageType())) {
            try {
                return Files.newInputStream(safeLocalPath(objectKey));
            } catch (Exception e) {
                throw new IllegalStateException("打开本地对象失败", e);
            }
        }
        if ("minio".equalsIgnoreCase(storageConfig.getStorageType())) {
            try {
                return minioClient.getObject(GetObjectArgs.builder()
                        .bucket(storageConfig.getBucketName())
                        .object(objectKey)
                        .build());
            } catch (Exception e) {
                throw new IllegalStateException("打开 MinIO 对象失败", e);
            }
        }
        throw new IllegalStateException("不支持的存储类型: " + storageConfig.getStorageType());
    }

    private void writeLocal(String objectKey, InputStream input) {
        Path target = safeLocalPath(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("对象临时上传失败", e);
        }
    }

    private Path safeLocalPath(String objectKey) {
        Path root = Paths.get(storageConfig.getLocalPath()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("对象路径不合法");
        }
        return target;
    }

    private void ensureBucket(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("存储桶不可用", e);
        }
    }
}
