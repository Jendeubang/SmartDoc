package com.javaee.aiservice.rag;

import com.javaee.aiservice.client.DocumentServiceClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionAwareRagServiceTest {

    @Test
    void diverseSearchKeepsOneHighRankedHitPerAccessibleDocumentBeforeFilling() {
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        DocumentServiceClient documents = mock(DocumentServiceClient.class);
        when(documents.getAccessibleDocumentIds()).thenReturn(List.of("doc-1", "doc-2"));
        when(knowledgeBase.hybridSearchWithRerank(any(), anyInt(),
                eq(Reranker.RerankStrategy.HYBRID), eq(DocumentSegmenter.StrategyType.CHAPTER), any()))
                .thenReturn(List.of(
                        Map.of("id", "chunk-1", "documentId", "doc-1", "content", "第一份文档片段一"),
                        Map.of("id", "chunk-2", "documentId", "doc-1", "content", "第一份文档片段二"),
                        Map.of("id", "chunk-3", "documentId", "doc-2", "content", "第二份文档片段"),
                        Map.of("id", "chunk-4", "documentId", "doc-3", "content", "无权限片段")));
        PermissionAwareRagService service = new PermissionAwareRagService(knowledgeBase, documents);

        List<Map<String, Object>> results = service.hybridSearchWithRerankDiverse(
                "比较两份文档", 2, "default", Reranker.RerankStrategy.HYBRID,
                DocumentSegmenter.StrategyType.CHAPTER);

        assertThat(results).extracting(result -> result.get("documentId"))
                .containsExactly("doc-1", "doc-2");
        assertThat(results).noneMatch(result -> "doc-3".equals(result.get("documentId")));
    }
}
