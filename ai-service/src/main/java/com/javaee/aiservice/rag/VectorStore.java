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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Autowired(required = false)
    private DocumentVectorizer documentVectorizer;

    @Autowired(required = false)
    private HnswIndexManager hnswIndexManager;

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
    // 存储：把文本交给 Spring AI 计算向量后写入 Redis 向量库
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
            indexInMemory(id, content, enrichedMetadata);
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

    // 语义检索：构造查询请求做相似度搜索，可附加元数据过滤表达式
    public List<Map<String, Object>> search(String queryText, int topK, Map<String, Object> filters) {
        log.info("搜索相似文档: topK={}", topK);
        if (queryText == null || queryText.isBlank()) {
            log.warn("查询文本为空，返回空结果");
            return Collections.emptyList();
        }

        // 优先使用进程内 HNSW；索引未就绪、维度不一致或运行时异常时回退到 RedisVectorStore。
        if (hnswIndexManager != null && documentVectorizer != null && hnswIndexManager.isReady()) {
            try {
                Optional<List<Map<String, Object>>> hnswResults = hnswIndexManager.search(
                        documentVectorizer.vectorize(queryText), topK, filters);
                if (hnswResults.isPresent()) {
                    log.info("HNSW 搜索完成，找到{}个结果", hnswResults.get().size());
                    return hnswResults.get();
                }
            } catch (Exception e) {
                log.warn("HNSW 搜索失败，回退到 RedisVectorStore", e);
            }
        }

        if (springAiVectorStore == null) {
            log.warn("VectorStore 未配置（Redis 不可用），返回空结果");
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

    // 删除指定 ID 的向量
    public void delete(String id) {
        log.info("删除向量: id={}", id);
        if (hnswIndexManager != null) {
            hnswIndexManager.remove(id);
        }
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

    private void indexInMemory(String id, String content, Map<String, Object> metadata) {
        if (hnswIndexManager == null || documentVectorizer == null || content == null || content.isBlank()) {
            return;
        }
        try {
            hnswIndexManager.upsert(id, documentVectorizer.vectorize(content), metadata);
        } catch (Exception e) {
            // RedisVectorStore 已经写入成功，HNSW 失败只影响加速层，后续由 warmup/Redis 回退保证可用性。
            log.warn("写入内存 HNSW 失败，保留 Redis 向量索引: id={}", id, e);
        }
    }

    // 把过滤条件拼成 Spring AI 过滤表达式，如 "userId == 'x' AND knowledgeBaseId == 'y'"
    private String buildFilterExpression(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        List<String> expressions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value.toString().isBlank()) continue;
            if (PermissionAwareRagService.ALLOWED_DOCUMENT_IDS_FILTER.equals(entry.getKey())) {
                if (!(value instanceof Collection<?> allowedIds) || allowedIds.isEmpty()) {
                    return "documentId == '__no_authorized_document__'";
                }
                String allowedExpression = allowedIds.stream()
                        .map(String::valueOf)
                        .map(id -> "documentId == '" + escapeRedisTagValue(id) + "'")
                        .collect(Collectors.joining(" OR "));
                expressions.add("(" + allowedExpression + ")");
                continue;
            }
            expressions.add(entry.getKey() + " == '" + escapeRedisTagValue(value.toString()) + "'");
        }
        return String.join(" AND ", expressions);
    }

    /**
     * Spring AI 会将该表达式转换为 RediSearch TAG 查询。短横线、空格等字符在
     * TAG 值中有特殊含义；不转义会导致企业 ID、知识库 ID 等合法业务标识检索失败。
     */
    private String escapeRedisTagValue(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\' || current == '-' || current == ' ' || current == '|' || current == '{'
                    || current == '}' || current == '(' || current == ')' || current == '[' || current == ']'
                    || current == ':' || current == '@' || current == '"' || current == '\'') {
                escaped.append('\\');
            }
            escaped.append(current);
        }
        return escaped.toString();
    }
}
