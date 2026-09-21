package com.javaee.aiservice.agent;

/**
 * 知识索引任务状态机。
 */
// 类职责：知识索引任务状态枚举，对应简历第3条「知识索引管理 Agent」的任务状态查询。
public enum KnowledgeIndexStatus {
    // 已创建，等待处理
    PENDING,
    // 正在解析文档内容
    PARSING,
    // 正在向量化并写入索引
    EMBEDDING,
    // 索引完成
    INDEXED,
    // 索引失败（可重试）
    FAILED
}
