package com.javaee.fileservice.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

/**
 * MinIO Bucket 自动初始化器
 * 启动时检查并创建项目所需的所有 bucket，免去手动创建
 */
@Component
public class MinioBucketInitializer {

    private static final Logger log = LoggerFactory.getLogger(MinioBucketInitializer.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket-name:file-service}")
    private String fileServiceBucket;

    /** 其他服务也会用到的 bucket，集中在此创建 */
    private static final List<String> EXTRA_BUCKETS = Arrays.asList(
            "doc-ai",      // AI 服务 + 文档服务
            "document",    // 文档服务默认 bucket
            "user-1",      // admin 用户的文档内容桶
            "user-2",      // user 用户的文档内容桶
            "file-service" // 文件服务上传桶
    );

    public MinioBucketInitializer(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @PostConstruct
    public void init() {
        log.info("开始检查 MinIO bucket...");

        List<String> buckets = new java.util.ArrayList<>();
        buckets.add(fileServiceBucket);
        buckets.addAll(EXTRA_BUCKETS);

        for (String bucket : buckets) {
            try {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(
                            MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Bucket 已创建: {}", bucket);
                } else {
                    log.info("Bucket 已存在: {}", bucket);
                }
            } catch (Exception e) {
                log.warn("Bucket 初始化失败: bucket={}, error={}", bucket, e.getMessage());
            }
        }

        log.info("MinIO bucket 检查完成");
    }
}
