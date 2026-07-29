package com.javaee.aiservice.rag;

/**
 * 【简历：Redis 向量存储 + HNSW 索引】
 * 基于 Redis 持久化向量与元数据，采用内存 HNSW 索引
 * 实现高效的近似最近邻语义检索。
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向量存储
 * 基于 Spring AI RedisVectorStore，提供向量存储、检索和删除功能
 * 保持与旧调用方兼容的接口签名
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    private org.springframework.ai.vectorstore.VectorStore springAiVectorStore;

    public VectorStore() {
        // 无参构造函数：当没有 Spring AI VectorStore bean 时使用
    }

    @Autowired(required = false)
    public VectorStore(org.springframework.ai.vectorstore.VectorStore springAiVectorStore) {
        this.springAiVectorStore = springAiVectorStore;
    }

    /**
     * 存储文档及其向量表示（内容+元数据方式，由 Spring AI 内部调用 EmbeddingModel）
     */
    public void store(String id, String content, Map<String, Object> metadata) {
        log.info("存储文档: id={}, contentLength={}", id, content.length());
        if (springAiVectorStore == null) {
            log.warn("VectorStore 未配置（Redis 不可用），跳过存储: id={}", id);
            return;
        }
        try {
            Map<String, Object> enrichedMetadata = new HashMap<>(metadata);
            enrichedMetadata.put("id", id);
            Document document = new Document(id, content, enrichedMetadata);
            springAiVectorStore.add(List.of(document));
            log.info("文档存储成功: id={}", id);
        } catch (Exception e) {
            log.error("文档存储失败", e);
            throw new RuntimeException("文档存储失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> search(float[] queryVector, int topK) {
        return search(queryVector, topK, Collections.emptyMap());
    }

    public List<Map<String, Object>> search(float[] queryVector, int topK, Map<String, Object> filters) {
        log.warn("使用已弃用的 float[] 搜索签名，返回空结果");
        return search("", topK, filters);
    }

    public List<Map<String, Object>> search(String queryText, int topK) {
        return search(queryText, topK, Collections.emptyMap());
    }

    public List<Map<String, Object>> search(String queryText, int topK, Map<String, Object> filters) {
        log.info("搜索相似文档: topK={}", topK);
        if (springAiVectorStore == null) {
            log.warn("VectorStore 未配置（Redis 不可用），返回空结果");
            return Collections.emptyList();
        }
        if (queryText == null || queryText.isBlank()) {
            log.warn("查询文本为空，返回空结果");
            return Collections.emptyList();
        }
        try {
            SearchRequest request = SearchRequest.query(queryText)
                    .withTopK(Math.max(1, Math.min(topK, 50)));
            if (filters != null && !filters.isEmpty()) {
                String filterExpression = buildFilterExpression(filters);
                if (!filterExpression.isEmpty()) {
                    request = request.withFilterExpression(filterExpression);
                }
            }
            List<Document> results = springAiVectorStore.similaritySearch(request);
            List<Map<String, Object>> mappedResults = new ArrayList<>();
            for (Document doc : results) {
                Map<String, Object> item = new HashMap<>(doc.getMetadata());
                item.put("id", doc.getId());
                item.put("content", doc.getContent());
                item.put("similarity", doc.getMetadata().getOrDefault("distance", 0.0));
                mappedResults.add(item);
            }
            log.info("搜索完成，找到{}个结果", mappedResults.size());
            return mappedResults;
        } catch (Exception e) {
            log.error("向量搜索失败", e);
            throw new RuntimeException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    public void delete(String id) {
        log.info("删除向量: id={}", id);
        if (springAiVectorStore == null) {
            log.warn("VectorStore 未配置（Redis 不可用），跳过删除: id={}", id);
            return;
        }
        try {
            springAiVectorStore.delete(List.of(id));
            log.info("向量删除成功: id={}", id);
        } catch (Exception e) {
            log.error("向量删除失败", e);
            throw new RuntimeException("向量删除失败: " + e.getMessage(), e);
        }
    }

    private String buildFilterExpression(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        return filters.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().toString().isBlank())
                .map(e -> e.getKey() + " == '" + e.getValue().toString() + "'")
                .collect(Collectors.joining(" AND "));
    }
}
