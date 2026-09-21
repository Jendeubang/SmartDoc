package com.javaee.aiservice.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HnswIndexManagerTest {

    private HnswIndexManager manager;

    @BeforeEach
    void setUp() {
        manager = new HnswIndexManager(16, 100, 64, 128);
        manager.markReady();
    }

    @Test
    void findsNearestItemsAndAppliesKnowledgeBaseFilter() {
        manager.upsert("doc-a", new float[]{1.0f, 0.0f}, metadata("org-a", "kb-a", "doc-a"));
        manager.upsert("doc-b", new float[]{0.9f, 0.1f}, metadata("org-a", "kb-a", "doc-b"));
        manager.upsert("doc-c", new float[]{0.0f, 1.0f}, metadata("org-a", "kb-b", "doc-c"));

        Optional<List<Map<String, Object>>> result = manager.search(
                new float[]{1.0f, 0.0f}, 2, Map.of("knowledgeBaseId", "kb-a"));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).extracting(item -> item.get("id"))
                .containsExactly("doc-a", "doc-b");
    }

    @Test
    void upsertReplacesOldVectorAndRemoveCleansIt() {
        manager.upsert("doc-a", new float[]{1.0f, 0.0f}, metadata("org-a", "kb-a", "doc-a"));
        manager.upsert("doc-a", new float[]{0.0f, 1.0f}, metadata("org-a", "kb-a", "doc-a"));

        Optional<List<Map<String, Object>>> replaced = manager.search(
                new float[]{0.0f, 1.0f}, 1, Map.of("knowledgeBaseId", "kb-a"));
        assertThat(replaced).isPresent();
        assertThat(replaced.orElseThrow()).extracting(item -> item.get("id"))
                .containsExactly("doc-a");

        assertThat(manager.remove("doc-a")).isTrue();
        Optional<List<Map<String, Object>>> afterDelete = manager.search(
                new float[]{0.0f, 1.0f}, 1, Map.of("knowledgeBaseId", "kb-a"));
        assertThat(afterDelete).isPresent();
        assertThat(afterDelete.orElseThrow()).isEmpty();
    }

    @Test
    void appliesAuthorizedDocumentFilterInsideHnswRecall() {
        manager.upsert("doc-a", new float[]{1.0f, 0.0f}, metadata("org-a", "kb-a", "doc-a"));
        manager.upsert("doc-b", new float[]{0.9f, 0.1f}, metadata("org-a", "kb-a", "doc-b"));

        Optional<List<Map<String, Object>>> result = manager.search(
                new float[]{1.0f, 0.0f}, 2,
                Map.of("knowledgeBaseId", "kb-a", "__allowedDocumentIds", Set.of("doc-b")));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).extracting(item -> item.get("id"))
                .containsExactly("doc-b");
    }

    @Test
    void rejectsInvalidVectorsWithoutCorruptingIndex() {
        assertThat(manager.upsert("empty", new float[0], Map.of())).isFalse();
        assertThat(manager.upsert("nan", new float[]{Float.NaN, 0.0f}, Map.of())).isFalse();
        manager.upsert("valid", new float[]{1.0f, 0.0f}, metadata("org-a", "kb-a", "valid"));
        assertThat(manager.size()).isEqualTo(1);
    }

    @Test
    void dimensionMismatchDoesNotReplaceExistingIndex() {
        manager.upsert("doc-a", new float[]{1.0f, 0.0f}, metadata("org-a", "kb-a", "doc-a"));

        assertThat(manager.upsert("doc-b", new float[]{1.0f, 0.0f, 0.0f},
                metadata("org-a", "kb-a", "doc-b"))).isFalse();
        assertThat(manager.size()).isEqualTo(1);
    }

    @Test
    void notReadyIndexReturnsFallbackSignal() {
        HnswIndexManager notReady = new HnswIndexManager(16, 100, 64, 128);
        notReady.upsert("doc-a", new float[]{1.0f, 0.0f}, metadata("org-a", "kb-a", "doc-a"));

        assertThat(notReady.search(new float[]{1.0f, 0.0f}, 1, Map.of())).isEmpty();
    }

    private Map<String, Object> metadata(String organizationId, String knowledgeBaseId, String documentId) {
        return Map.of(
                "organizationId", organizationId,
                "knowledgeBaseId", knowledgeBaseId,
                "documentId", documentId);
    }
}
