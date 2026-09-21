package com.javaee.aiservice.agent;

/**
 * 【简历：索引任务模型】
 * 定义异步知识索引任务的状态、进度、文档 ID、元数据等。
 */

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识索引任务的内存状态记录。
 * 生产环境建议落库，这里提供最小可用版本以便业务串通。
 */
// 类职责：知识索引任务对象，对应简历第3条「知识索引管理 Agent」——
// 持有 jobId/documentId/userId/knowledgeBaseId/organizationId 及状态、进度、重试次数等字段。
public class KnowledgeIndexJob {

    private final String jobId;
    private final String documentId;
    private final String userId;
    private final String knowledgeBaseId;
    private final String organizationId;
    private final long createdAt;
    private volatile KnowledgeIndexStatus status = KnowledgeIndexStatus.PENDING;
    private volatile long updatedAt;
    private volatile int attempts;
    private volatile String errorMessage;
    private volatile Map<String, Object> quality = new LinkedHashMap<>();
    private volatile int progress;
    private volatile String contentHash;
    private volatile boolean reused;

    public KnowledgeIndexJob(String jobId, String documentId, String userId, String knowledgeBaseId) {
        this(jobId, documentId, userId, knowledgeBaseId, null);
    }

    public KnowledgeIndexJob(String jobId, String documentId, String userId, String knowledgeBaseId, String organizationId) {
        this.jobId = jobId;
        this.documentId = documentId;
        this.userId = userId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.organizationId = organizationId;
        this.createdAt = Instant.now().toEpochMilli();
        this.updatedAt = this.createdAt;
    }

    public String getJobId() {
        return jobId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getUserId() {
        return userId;
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String getOrganizationId() { return organizationId; }

    public KnowledgeIndexStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeIndexStatus status) {
        this.status = status;
        this.updatedAt = Instant.now().toEpochMilli();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public void setAttempts(int attempts) {
        this.attempts = Math.max(0, attempts);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getQuality() {
        return quality;
    }

    public void setQuality(Map<String, Object> quality) {
        this.quality = quality != null ? quality : new LinkedHashMap<>();
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(100, progress));
        this.updatedAt = Instant.now().toEpochMilli();
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public boolean isReused() {
        return reused;
    }

    public void setReused(boolean reused) {
        this.reused = reused;
    }

    // 将任务当前状态导出为 Map 快照，供 Redis 持久化与状态查询使用。
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", jobId);
        map.put("documentId", documentId);
        map.put("userId", userId);
        map.put("knowledgeBaseId", knowledgeBaseId);
        map.put("organizationId", organizationId);
        map.put("status", status.name());
        map.put("attempts", attempts);
        map.put("progress", progress);
        map.put("contentHash", contentHash);
        map.put("reused", reused);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        map.put("errorMessage", errorMessage);
        map.put("quality", quality);
        return map;
    }
}
