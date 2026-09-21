package com.javaee.aiservice.rag;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VectorStoreFilterTest {

    @Test
    void buildsRedisExpressionForAuthorizedDocumentSetAndTenantScope() {
        VectorStore vectorStore = new VectorStore();

        String expression = ReflectionTestUtils.invokeMethod(vectorStore, "buildFilterExpression", Map.of(
                "knowledgeBaseId", "kb-1",
                "organizationId", "org-1",
                PermissionAwareRagService.ALLOWED_DOCUMENT_IDS_FILTER, Set.of("doc-1", "doc-2")));

        assertThat(expression).contains("knowledgeBaseId == 'kb\\-1'");
        assertThat(expression).contains("organizationId == 'org\\-1'");
        assertThat(expression).contains("documentId == 'doc\\-1'");
        assertThat(expression).contains("documentId == 'doc\\-2'");
        assertThat(expression).contains(" OR ");
    }

    @Test
    void emptyAuthorizedSetProducesAnImpossibleRedisExpression() {
        VectorStore vectorStore = new VectorStore();

        String expression = ReflectionTestUtils.invokeMethod(vectorStore, "buildFilterExpression", Map.of(
                PermissionAwareRagService.ALLOWED_DOCUMENT_IDS_FILTER, List.of()));

        assertThat(expression).isEqualTo("documentId == '__no_authorized_document__'");
    }
}
