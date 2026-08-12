package com.javaee.aiservice.rag;

import com.javaee.aiservice.client.DocumentServiceClient;
import com.javaee.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** RAG authorization is derived from document-service on every request. */
@Service
public class PermissionAwareRagService {
    private final KnowledgeBase knowledgeBase;
    private final DocumentServiceClient documents;

    public PermissionAwareRagService(KnowledgeBase knowledgeBase, DocumentServiceClient documents) {
        this.knowledgeBase = knowledgeBase;
        this.documents = documents;
    }

    public void assertCanIndex(String documentId) { documents.assertDocumentAccess(documentId, true); }
    public void assertCanRead(String documentId) { documents.assertDocumentAccess(documentId, false); }

    public String normalizeKnowledgeBaseId(String value) {
        String id = value == null || value.isBlank() ? "default" : value.trim();
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException("知识库 ID 只能包含字母、数字、下划线和短横线");
        }
        return id;
    }

    public List<Map<String, Object>> search(String query, int topK, String knowledgeBaseId,
                                             DocumentSegmenter.StrategyType strategy) {
        return restrict(knowledgeBase.search(query, candidateSize(topK), strategy, kbFilter(knowledgeBaseId)), topK);
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK, String knowledgeBaseId,
                                                   DocumentSegmenter.StrategyType strategy) {
        return restrict(knowledgeBase.hybridSearch(query, candidateSize(topK), strategy, kbFilter(knowledgeBaseId)), topK);
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK, String knowledgeBaseId,
                                                             Reranker.RerankStrategy rerank,
                                                             DocumentSegmenter.StrategyType strategy) {
        return restrict(knowledgeBase.hybridSearchWithRerank(query, candidateSize(topK), rerank, strategy,
                kbFilter(knowledgeBaseId)), topK);
    }

    public List<String> accessibleIndexedDocumentIds(String knowledgeBaseId) {
        String safeKnowledgeBaseId = normalizeKnowledgeBaseId(knowledgeBaseId);
        Set<String> allowed = new HashSet<>(documents.getAccessibleDocumentIds());
        return knowledgeBase.getAllDocumentIds().stream()
                .filter(allowed::contains)
                .filter(id -> safeKnowledgeBaseId.equals(String.valueOf(
                        knowledgeBase.getDocumentMetadata(id).getOrDefault("knowledgeBaseId", "default"))))
                .toList();
    }

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

    private List<Map<String, Object>> restrict(List<Map<String, Object>> candidates, int topK) {
        Set<String> allowed = new HashSet<>(documents.getAccessibleDocumentIds());
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

    private String sourceDocumentId(Map<String, Object> candidate) {
        Object documentId = candidate.get("documentId");
        if (documentId != null && !documentId.toString().isBlank()) return documentId.toString();
        Object id = candidate.get("id");
        return id == null ? null : id.toString();
    }

    private Map<String, Object> kbFilter(String id) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("knowledgeBaseId", normalizeKnowledgeBaseId(id));
        return filter;
    }

    private int candidateSize(int topK) { return Math.max(10, Math.min(50, topK * 8)); }
}
