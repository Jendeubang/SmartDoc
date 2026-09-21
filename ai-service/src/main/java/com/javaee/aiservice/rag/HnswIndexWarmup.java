package com.javaee.aiservice.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 使用 Redis 中现有文档补建进程内 HNSW 索引。
 * 已经有 HNSW 快照的向量不会重复调用 Embedding API；只有历史遗留且尚未快照的
 * 文档分段才会补向量。补建期间 VectorStore 保持 Redis 回退，补建完成后才切换到 HNSW。
 */
@Component
public class HnswIndexWarmup {

    private static final Logger log = LoggerFactory.getLogger(HnswIndexWarmup.class);
    // DashScope text-embedding-v4 的单次输入上限为 10，不能按常见的 16/32 批量发送。
    private static final int BATCH_SIZE = 10;

    private final HnswIndexManager hnswIndexManager;
    private final KnowledgeBase knowledgeBase;
    private final DocumentVectorizer vectorizer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rag-hnsw-warmup");
        thread.setDaemon(true);
        return thread;
    });

    public HnswIndexWarmup(HnswIndexManager hnswIndexManager,
                           KnowledgeBase knowledgeBase,
                           DocumentVectorizer vectorizer) {
        this.hnswIndexManager = hnswIndexManager;
        this.knowledgeBase = knowledgeBase;
        this.vectorizer = vectorizer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWarmup() {
        if (!hnswIndexManager.isEnabled()) {
            return;
        }
        executor.submit(this::warmup);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void warmup() {
        hnswIndexManager.beginWarmup();
        try {
            List<String> documentIds = knowledgeBase.getAllDocumentIds();
            List<PendingVector> batch = new ArrayList<>(BATCH_SIZE);
            for (String documentId : documentIds) {
                Map<String, Object> documentMetadata = new HashMap<>(knowledgeBase.getDocumentMetadata(documentId));
                documentMetadata.putIfAbsent("documentId", documentId);
                List<String> segmentIds = knowledgeBase.getSegmentIds(documentId);

                if (segmentIds.isEmpty()) {
                    addIfMissing(batch, documentId, knowledgeBase.getDocumentContent(documentId), documentMetadata);
                } else {
                    for (String segmentId : segmentIds) {
                        Map<String, Object> metadata = new HashMap<>(documentMetadata);
                        metadata.put("documentId", documentId);
                        addIfMissing(batch, segmentId, knowledgeBase.getSegmentContent(segmentId), metadata);
                        if (batch.size() >= BATCH_SIZE) {
                            flush(batch);
                        }
                    }
                }
                if (batch.size() >= BATCH_SIZE) {
                    flush(batch);
                }
            }
            flush(batch);
            hnswIndexManager.markReady();
            log.info("HNSW 历史文档补建完成: documents={}, indexedItems={}",
                    documentIds.size(), hnswIndexManager.size());
        } catch (Exception e) {
            // 保持 ready=false，所有检索自动走原 Redis 向量库，不能因为 HNSW 失败影响主链路。
            log.warn("HNSW 历史文档补建失败，将继续使用 Redis 向量检索", e);
        }
    }

    private void addIfMissing(List<PendingVector> batch, String id, String content,
                              Map<String, Object> metadata) {
        if (id == null || id.isBlank() || content == null || content.isBlank()
                || hnswIndexManager.contains(id)) {
            return;
        }
        batch.add(new PendingVector(id, content, metadata));
    }

    private void flush(List<PendingVector> batch) {
        if (batch.isEmpty()) {
            return;
        }
        String[] texts = batch.stream().map(PendingVector::content).toArray(String[]::new);
        float[][] vectors = vectorizer.vectorizeBatch(texts);
        if (vectors.length != batch.size()) {
            throw new IllegalStateException("Embedding 返回数量与输入数量不一致");
        }
        for (int i = 0; i < batch.size(); i++) {
            PendingVector pending = batch.get(i);
            hnswIndexManager.upsert(pending.id(), vectors[i], pending.metadata());
        }
        batch.clear();
    }

    private record PendingVector(String id, String content, Map<String, Object> metadata) {
    }
}
