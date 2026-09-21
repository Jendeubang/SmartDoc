package com.javaee.aiservice.rag;

import com.javaee.aiservice.client.DocumentServiceClient;
import com.javaee.common.config.security.TenantContext;
import com.javaee.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** RAG authorization is derived from document-service on every request. */
// 权限感知 RAG 服务（简历第 2 条「权限感知检索」）：检索结果实时按 document-service 返回的可访问文档集合过滤
@Service
public class PermissionAwareRagService {
    static final String ALLOWED_DOCUMENT_IDS_FILTER = "__allowedDocumentIds";

    private final KnowledgeBase knowledgeBase;
    private final DocumentServiceClient documents;

    public PermissionAwareRagService(KnowledgeBase knowledgeBase, DocumentServiceClient documents) {
        this.knowledgeBase = knowledgeBase;
        this.documents = documents;
    }

    public void assertCanIndex(String documentId) { documents.assertDocumentAccess(documentId, true); }
    public void assertCanRead(String documentId) { documents.assertDocumentAccess(documentId, false); }

    // 规范化知识库 ID：空值兜底为 default，并校验只含合法字符
    public String normalizeKnowledgeBaseId(String value) {
        String id = value == null || value.isBlank() ? "default" : value.trim();
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException("知识库 ID 只能包含字母、数字、下划线和短横线");
        }
        return id;
    }

    // 向量检索入口：先取更大候选集，再按可访问文档集合过滤并截断到 topK
    public List<Map<String, Object>> search(String query, int topK, String knowledgeBaseId,
                                             DocumentSegmenter.StrategyType strategy) {
        Set<String> allowed = accessibleDocumentIds();
        if (allowed.isEmpty()) return List.of();
        return restrict(knowledgeBase.search(query, candidateSize(topK), strategy,
                authorizedFilter(knowledgeBaseId, allowed)), topK, allowed);
    }

    // 混合检索入口：向量+BM25 召回后按权限过滤
    public List<Map<String, Object>> hybridSearch(String query, int topK, String knowledgeBaseId,
                                                   DocumentSegmenter.StrategyType strategy) {
        Set<String> allowed = accessibleDocumentIds();
        if (allowed.isEmpty()) return List.of();
        return restrict(knowledgeBase.hybridSearch(query, candidateSize(topK), strategy,
                authorizedFilter(knowledgeBaseId, allowed)), topK, allowed);
    }

    // 混合检索 + Rerank 精排，再按权限过滤
    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK, String knowledgeBaseId,
                                                             Reranker.RerankStrategy rerank,
                                                             DocumentSegmenter.StrategyType strategy) {
        Set<String> allowed = accessibleDocumentIds();
        if (allowed.isEmpty()) return List.of();
        return restrict(knowledgeBase.hybridSearchWithRerank(query, candidateSize(topK), rerank, strategy,
                authorizedFilter(knowledgeBaseId, allowed)), topK, allowed);
    }

    /**
     * Cross-document retrieval keeps the highest-ranked hit from each authorized document first,
     * then fills remaining slots by score. This prevents one long document from occupying every hit.
     */
    // 多样化精排检索：过滤后每个授权文档优先保留最高分命中，避免单一长文档霸占全部结果
    public List<Map<String, Object>> hybridSearchWithRerankDiverse(String query, int topK, String knowledgeBaseId,
                                                                    Reranker.RerankStrategy rerank,
                                                                    DocumentSegmenter.StrategyType strategy) {
        Set<String> allowed = accessibleDocumentIds();
        if (allowed.isEmpty()) return List.of();
        return restrictDiverse(knowledgeBase.hybridSearchWithRerank(query, candidateSize(topK), rerank, strategy,
                authorizedFilter(knowledgeBaseId, allowed)), topK, allowed);
    }

    // 返回已建索引且当前用户可访问的文档 ID 列表（按知识库维度过滤）
    public List<String> accessibleIndexedDocumentIds(String knowledgeBaseId) {
        String safeKnowledgeBaseId = normalizeKnowledgeBaseId(knowledgeBaseId);
        Set<String> allowed = new HashSet<>(documents.getAccessibleDocumentIds());
        return knowledgeBase.getAllDocumentIds().stream()
                .filter(allowed::contains)
                .filter(id -> safeKnowledgeBaseId.equals(String.valueOf(
                        knowledgeBase.getDocumentMetadata(id).getOrDefault("knowledgeBaseId", "default"))))
                .toList();
    }

    // 统计当前用户可访问范围内的文档数、分段数与总字符数
    public Map<String, Object> statistics(String knowledgeBaseId) {
        List<String> ids = accessibleIndexedDocumentIds(knowledgeBaseId);
        long chars = 0;
        int segments = 0;
        for (String id : ids) {
            String content = knowledgeBase.getDocumentContent(id);
            if (content != null) chars += content.length();
            segments += knowledgeBase.getSegmentIds(id).size();
        }
        return Map.of("documentCount", ids.size(), "segmentCount", segments, "totalContentSize", chars,
                "permissionMode", "document-service-live-scope");
    }

    // 核心权限过滤：仅保留所属文档在可访问集合内的候选结果
    private List<Map<String, Object>> restrict(List<Map<String, Object>> candidates, int topK,
                                               Set<String> allowed) {
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String documentId = sourceDocumentId(candidate);
            if (documentId != null && allowed.contains(documentId)) {
                Map<String, Object> enriched = new HashMap<>(candidate);
                enriched.put("documentId", documentId);
                safe.add(enriched);
                if (safe.size() >= Math.max(1, topK)) break;
            }
        }
        return safe;
    }

    // 多样化过滤：先每文档取最高分一条，再用剩余分块补足 topK
    private List<Map<String, Object>> restrictDiverse(List<Map<String, Object>> candidates, int topK,
                                                      Set<String> allowed) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String documentId = sourceDocumentId(candidate);
            if (documentId != null && allowed.contains(documentId)) {
                Map<String, Object> enriched = new LinkedHashMap<>(candidate);
                enriched.put("documentId", documentId);
                ranked.add(enriched);
            }
        }

        int limit = Math.max(1, topK);
        List<Map<String, Object>> safe = new ArrayList<>();
        Set<String> representedDocuments = new LinkedHashSet<>();
        Set<String> representedChunks = new LinkedHashSet<>();
        for (Map<String, Object> candidate : ranked) {
            String documentId = sourceDocumentId(candidate);
            if (representedDocuments.add(documentId)) {
                safe.add(candidate);
                representedChunks.add(String.valueOf(candidate.get("id")));
                if (safe.size() >= limit) return safe;
            }
        }
        for (Map<String, Object> candidate : ranked) {
            if (representedChunks.add(String.valueOf(candidate.get("id")))) {
                safe.add(candidate);
                if (safe.size() >= limit) break;
            }
        }
        return safe;
    }

    // 从候选结果中解析所属文档 ID（优先取 documentId 字段，其次取 id）
    private String sourceDocumentId(Map<String, Object> candidate) {
        Object documentId = candidate.get("documentId");
        if (documentId != null && !documentId.toString().isBlank()) return documentId.toString();
        Object id = candidate.get("id");
        return id == null ? null : id.toString();
    }

    // 构造 knowledgeBaseId 过滤条件，限定检索只落在指定知识库内
    private Map<String, Object> kbFilter(String id) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("knowledgeBaseId", normalizeKnowledgeBaseId(id));
        return filter;
    }

    private Set<String> accessibleDocumentIds() {
        return new HashSet<>(documents.getAccessibleDocumentIds());
    }

    /**
     * The authorization set is passed into vector/BM25 recall, so an unauthorized
     * segment cannot reach rerank or prompt construction and be filtered only later.
     */
    private Map<String, Object> authorizedFilter(String knowledgeBaseId, Set<String> allowed) {
        Map<String, Object> filter = kbFilter(knowledgeBaseId);
        filter.put(ALLOWED_DOCUMENT_IDS_FILTER, Set.copyOf(allowed));
        String organizationId = TenantContext.get();
        if (organizationId != null && !organizationId.isBlank()) {
            filter.put("organizationId", organizationId);
        }
        return filter;
    }

    // 放大候选集：多召回一些结果供后续权限过滤，避免过滤后不足 topK
    // 召回候选只保留足够的权限过滤余量，避免 topK=5 时无意义地重排 40 条以上结果。
    // Recall@5 需要通过评测集复核；若权限过滤后的命中不足，再将倍数调回 5。
    private int candidateSize(int topK) { return Math.max(10, Math.min(30, topK * 4)); }
}
