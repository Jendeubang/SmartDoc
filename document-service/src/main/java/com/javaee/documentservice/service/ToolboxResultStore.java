package com.javaee.documentservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.entity.ToolboxJob;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 工具箱结果对象存储。结果正文只落 MinIO，任务表只保存对象键和媒体信息。
 */
@Service
public class ToolboxResultStore {
    private final MinioClient minio;
    private final String bucket;

    public ToolboxResultStore(MinioClient minio,
                              @Value("${minio.bucket-name:document}") String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    public String put(ToolboxJob job, byte[] content, String contentType) {
        String key = objectKey(job);
        try {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
            return key;
        } catch (Exception exception) {
            throw new BusinessException("保存工具箱结果失败: " + safeMessage(exception));
        }
    }

    public byte[] get(ToolboxJob job) {
        if (job == null || job.getResultObjectKey() == null || job.getResultObjectKey().isBlank()) {
            throw new BusinessException("The result file is not ready yet");
        }
        try (var stream = minio.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(job.getResultObjectKey())
                .build())) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new BusinessException("读取工具箱结果失败: " + safeMessage(exception));
        } catch (Exception exception) {
            throw new BusinessException("读取工具箱结果失败: " + safeMessage(exception));
        }
    }

    public void delete(ToolboxJob job) {
        if (job == null || job.getResultObjectKey() == null || job.getResultObjectKey().isBlank()) {
            return;
        }
        try {
            minio.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(job.getResultObjectKey())
                    .build());
        } catch (Exception exception) {
            throw new BusinessException("删除工具箱结果失败: " + safeMessage(exception));
        }
    }

    private String objectKey(ToolboxJob job) {
        return "toolbox-results/" + safeSegment(job.getOrganizationId()) + "/"
                + job.getUserId() + "/" + safeSegment(job.getJobId()) + "/"
                + safeSegment(job.getFileName());
    }

    private void ensureBucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private String safeSegment(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Unknown error" : exception.getMessage();
    }
}
