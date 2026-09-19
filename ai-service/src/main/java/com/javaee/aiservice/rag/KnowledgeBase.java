package com.javaee.aiservice.rag;

/**
 * 【简历：RAG 知识库模型】
 * 知识库领域模型，管理知识库元信息、分段列表及相关配置。
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

// RAG 检索核心（简历第 2 条）：负责文档入库、向量+BM25 混合检索、查询扩展、上下文贯通与知识库统计
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final String DOCUMENT_PREFIX = "doc:";
    private static final String CONTENT_PREFIX = "content:";
    private static final String SEGMENT_PREFIX = "segment:";
    private static final String DOC_SEGMENTS_PREFIX = "doc_segments:";
    // [NEW] 分块元数据前缀，与 VectorStore 保持一致
    private static final String METADATA_PREFIX = "metadata:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private Reranker reranker;

    @Autowired
    private DocumentSegmenter documentSegmenter;

    /**
     * 本地 BM25 索引：启动时从 Redis 构建，文档增删时增量维护。
     * 这样查询阶段不再对 Redis 执行 SCAN + 逐条 GET。
     */
    private final Map<String, IndexedText> bm25Index = new java.util.concurrent.ConcurrentHashMap<>();

    /** 向量召回与本地 BM25 并行执行，线程数固定，避免请求高峰无限创建线程。 */
    private final ExecutorService retrievalExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "rag-retrieval");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void rebuildBm25Index() {
        try {
            for (String key : scanKeys(CONTENT_PREFIX + "*", 1000)) {
                indexRedisText(key, getDocumentMetadata(key.substring(CONTENT_PREFIX.length())));
            }
            for (String key : scanKeys(SEGMENT_PREFIX + "*", 1000)) {
                indexRedisText(key, vectorStoreMetadata(key.substring(SEGMENT_PREFIX.length())));
            }
            log.info("BM25 本地索引构建完成: entries={}", bm25Index.size());
        } catch (Exception e) {
            log.warn("BM25 本地索引构建失败，后续请求将尝试从 Redis 恢复", e);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdownRetrievalExecutor() {
        retrievalExecutor.shutdownNow();
    }

    public void addDocument(String documentId, String content, Map<String, Object> metadata) {
        addDocumentWithSegment(documentId, content, metadata, DocumentSegmenter.StrategyType.AUTO);
    }

    // 写入文档：保存正文与元数据，分段后逐段入库并登记分段 ID 列表
    public void addDocumentWithSegment(String documentId, String content, Map<String, Object> metadata,
                                       DocumentSegmenter.StrategyType strategyType) {
        log.info("添加文档到知识库: documentId={}, strategy={}", documentId, strategyType);

        try {
            String docKey = DOCUMENT_PREFIX + documentId;
            String contentKey = CONTENT_PREFIX + documentId;

            Map<String, Object> docMetadata = normalizeMetadata(metadata);
            docMetadata.put("strategy", strategyType.name());
            docMetadata.put("totalLength", content.length());

            redisTemplate.opsForValue().set(contentKey, content);
            redisTemplate.opsForHash().putAll(docKey, docMetadata);
            indexText(contentKey, documentId, content, docMetadata);

            List<SegmentStrategy.Segment> segments = documentSegmenter.segment(documentId, content, strategyType);

            if (segments.isEmpty()) {
                log.warn("文档分段结果为空，直接存储完整文档");
                // 使用文本内容存储，由 VectorStore 内部调用 EmbeddingModel 计算向量
                vectorStore.store(documentId, content, docMetadata);
                return;
            }

            List<String> segmentIds = new ArrayList<>();
            for (SegmentStrategy.Segment segment : segments) {
                String segmentId = segment.getSegmentId();
                segmentIds.add(segmentId);

                String segmentContentKey = SEGMENT_PREFIX + segmentId;
                redisTemplate.opsForValue().set(segmentContentKey, segment.getContent());

                Map<String, Object> segmentMetadata = new HashMap<>(docMetadata);
                segmentMetadata.put("documentId", documentId);
                segmentMetadata.put("segmentIndex", segment.getIndex());
                segmentMetadata.put("segmentTitle", segment.getTitle());
                segmentMetadata.put("charCount", segment.getCharCount());
                // [NEW] 保存语义分块的相邻链接，支持检索时上下文贯通
                if (segment.getPrevChunkId() != null) {
                    segmentMetadata.put("prevChunkId", segment.getPrevChunkId());
                }
                if (segment.getNextChunkId() != null) {
                segmentMetadata.put("nextChunkId", segment.getNextChunkId());
                }

                indexText(segmentContentKey, segmentId, segment.getContent(), segmentMetadata);

                // 使用文本内容存储，由 VectorStore 内部调用 EmbeddingModel 计算向量
                vectorStore.store(segmentId, segment.getContent(), segmentMetadata);
            }

            redisTemplate.opsForValue().set(DOC_SEGMENTS_PREFIX + documentId, segmentIds);

            log.info("文档添加成功: documentId={}, 分段数={}", documentId, segments.size());
        } catch (Exception e) {
            log.error("添加文档失败", e);
            throw new RuntimeException("添加文档失败: " + e.getMessage(), e);
        }
    }

    public void addDocument(String documentId, String content, Map<String, Object> metadata,
                           DocumentSegmenter.StrategyType strategyType) {
        addDocumentWithSegment(documentId, content, metadata, strategyType);
    }

    // 删除文档及其所有分段、向量和相关元数据
    public void removeDocument(String documentId) {
        log.info("从知识库移除文档: documentId={}", documentId);

        try {
            List<String> segmentIds = getSegmentIds(documentId);

            for (String segmentId : segmentIds) {
                redisTemplate.delete(SEGMENT_PREFIX + segmentId);
                vectorStore.delete(segmentId);
            }

            // 无分段文档会直接以 documentId 写入向量库，删除时也必须清理该向量。
            vectorStore.delete(documentId);

            redisTemplate.delete(DOC_SEGMENTS_PREFIX + documentId);
            redisTemplate.delete(DOCUMENT_PREFIX + documentId);
            redisTemplate.delete(CONTENT_PREFIX + documentId);
            bm25Index.remove(CONTENT_PREFIX + documentId);
            for (String segmentId : segmentIds) {
                bm25Index.remove(SEGMENT_PREFIX + segmentId);
            }

            log.info("文档移除成功: documentId={}, 删除了{}个分段", documentId, segmentIds.size());
        } catch (Exception e) {
            log.error("移除文档失败", e);
            throw new RuntimeException("移除文档失败: " + e.getMessage(), e);
        }
    }

    public String getDocumentContent(String documentId) {
        try {
            return (String) redisTemplate.opsForValue().get(CONTENT_PREFIX + documentId);
        } catch (Exception e) {
            log.warn("获取文档内容失败", e);
            return null;
        }
    }

    public String getSegmentContent(String segmentId) {
        try {
            return (String) redisTemplate.opsForValue().get(SEGMENT_PREFIX + segmentId);
        } catch (Exception e) {
            log.warn("获取分段内容失败", e);
            return null;
        }
    }

    public List<String> getSegmentIds(String documentId) {
        try {
            Object segmentIdsObj = redisTemplate.opsForValue().get(DOC_SEGMENTS_PREFIX + documentId);
            if (segmentIdsObj == null) {
                return Collections.emptyList();
            }
            if (segmentIdsObj instanceof List) {
                return ((List<?>) segmentIdsObj).stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("获取文档分段ID列表失败", e);
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getDocumentMetadata(String documentId) {
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(DOCUMENT_PREFIX + documentId);
            Map<String, Object> metadata = new HashMap<>();
            for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                metadata.put(entry.getKey().toString(), entry.getValue());
            }
            return metadata;
        } catch (Exception e) {
            log.warn("获取文档元数据失败", e);
            return Collections.emptyMap();
        }
    }

    public List<Map<String, Object>> getDocumentSegments(String documentId) {
        log.info("获取文档分段: documentId={}", documentId);

        try {
            List<String> segmentIds = getSegmentIds(documentId);
            List<Map<String, Object>> segments = new ArrayList<>();

            for (String segmentId : segmentIds) {
                Map<String, Object> segmentInfo = new HashMap<>();
                segmentInfo.put("segmentId", segmentId);
                segmentInfo.put("content", getSegmentContent(segmentId));
                segments.add(segmentInfo);
            }

            return segments;
        } catch (Exception e) {
            log.error("获取文档分段失败", e);
            throw new RuntimeException("获取文档分段失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> search(String query, int topK) {
        return search(query, topK, DocumentSegmenter.StrategyType.CHAPTER);
    }

    public List<Map<String, Object>> search(String query, int topK,
                                            DocumentSegmenter.StrategyType strategyType) {
        return search(query, topK, strategyType, Collections.emptyMap());
    }

    // 纯向量检索：带查询扩展与空结果兜底，最后为命中分块贯通上下文
    public List<Map<String, Object>> search(String query, int topK,
                                            DocumentSegmenter.StrategyType strategyType,
                                            Map<String, Object> filters) {
        log.info("搜索知识库: query={}, topK={}, strategy={}", query, topK, strategyType);

        try {
            // [NEW] 查询扩展：生成同义变体提高召回
            String expandedQuery = expandQuery(query);
            String effectiveQuery = expandedQuery != null ? expandedQuery : query;

            // 由 VectorStore 内部调用 EmbeddingModel 计算查询向量
            List<Map<String, Object>> results = vectorStore.search(effectiveQuery, topK, filters);

            // [NEW] 空结果兜底：回退到原始查询 + 扩大候选数
            if (results.isEmpty() && expandedQuery != null) {
                log.info("扩展查询无结果，回退到原始查询: query={}", query);
                results = vectorStore.search(query, topK * 2, filters);
            }

            for (Map<String, Object> result : results) {
                String id = (String) result.get("id");
                String content = getSegmentContent(id);
                if (content == null) {
                    content = getDocumentContent(id);
                }
                // Redis 短暂不可用时保留 HNSW 返回的已有内容；正常情况下用 Redis 中的最新内容覆盖。
                if (content != null) {
                    result.put("content", content);
                }
            }

            // [NEW] 上下文贯通：为匹配的分块拉取相邻分块
            results = bridgeContext(results);

            return results;
        } catch (Exception e) {
            log.error("知识库搜索失败", e);
            throw new RuntimeException("知识库搜索失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK) {
        return hybridSearch(query, topK, DocumentSegmenter.StrategyType.CHAPTER);
    }

    public List<Map<String, Object>> hybridSearch(String query, int topK,
                                                   DocumentSegmenter.StrategyType strategyType) {
        return hybridSearch(query, topK, strategyType, Collections.emptyMap());
    }

    // 混合检索：向量 + BM25 双路召回，去重合并取 topK，再贯通上下文
    public List<Map<String, Object>> hybridSearch(String query, int topK,
                                                   DocumentSegmenter.StrategyType strategyType,
                                                   Map<String, Object> filters) {
        return hybridSearchInternal(query, topK, strategyType, filters, true);
    }

    private List<Map<String, Object>> hybridSearchInternal(String query, int topK,
                                                            DocumentSegmenter.StrategyType strategyType,
                                                            Map<String, Object> filters,
                                                            boolean includeContext) {
        log.info("混合检索: query={}, topK={}, strategy={}", query, topK, strategyType);

        try {
            // [NEW] 查询扩展
            String expandedQuery = expandQuery(query);
            String effectiveQuery = expandedQuery != null ? expandedQuery : query;

            // 向量检索与本地 BM25 并行，降低两条召回链路的总等待时间。
            CompletableFuture<List<Map<String, Object>>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> vectorStore.search(effectiveQuery, topK * 3, filters), retrievalExecutor);
            CompletableFuture<List<Map<String, Object>>> bm25Future = CompletableFuture.supplyAsync(
                    () -> bm25Search(effectiveQuery, topK * 3, filters), retrievalExecutor);
            List<Map<String, Object>> vectorResults = vectorFuture.join();
            List<Map<String, Object>> bm25Results = bm25Future.join();

            // [NEW] 空结果兜底：回退到原始查询 + 扩大检索范围
            if (vectorResults.isEmpty() && bm25Results.isEmpty() && expandedQuery != null) {
                log.info("扩展查询无结果，回退到原始查询: query={}", query);
                vectorResults = vectorStore.search(query, topK * 5, filters);
                bm25Results = bm25Search(query, topK * 5, filters);
            }

            Set<String> seenIds = new HashSet<>();
            List<Map<String, Object>> combinedResults = new ArrayList<>();

            for (Map<String, Object> result : vectorResults) {
                String id = (String) result.get("id");
                if (!seenIds.contains(id)) {
                    seenIds.add(id);
                    Map<String, Object> mutable = new HashMap<>(result);
                    String content = getSegmentContent(id);
                    if (content == null) {
                        content = getDocumentContent(id);
                    }
                    if (content != null) {
                        mutable.put("content", content);
                    }
                    mutable.put("source", "vector");
                    combinedResults.add(mutable);
                }
            }

            for (Map<String, Object> result : bm25Results) {
                String id = (String) result.get("id");
                if (!seenIds.contains(id)) {
                    seenIds.add(id);
                    Map<String, Object> mutable = new HashMap<>(result);
                    String content = getSegmentContent(id);
                    if (content == null) {
                        content = getDocumentContent(id);
                    }
                    mutable.put("content", content);
                    mutable.put("source", "bm25");
                    combinedResults.add(mutable);
                }
            }

            List<Map<String, Object>> finalResults = combinedResults.subList(0, Math.min(topK, combinedResults.size()));
            return includeContext ? bridgeContext(finalResults) : finalResults;

        } catch (Exception e) {
            log.error("混合检索失败", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            DocumentSegmenter.StrategyType strategyType) {
        return hybridSearchWithRerank(query, topK, rerankStrategy, strategyType, Collections.emptyMap());
    }

    // 混合检索 + Rerank 精排：先召回更大候选集，再用重排序器精排取 topK
    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            DocumentSegmenter.StrategyType strategyType,
                                                            Map<String, Object> filters) {
        log.info("混合检索加重排序: query={}, topK={}, strategy={}, rerankStrategy={}",
                query, topK, strategyType, rerankStrategy);

        try {
            // 先召回再精排；上下文贯通放到精排后，避免给大量候选读取相邻分块。
            List<Map<String, Object>> candidates = hybridSearchWithoutContext(query, topK * 3, strategyType, filters);
            List<Map<String, Object>> results = reranker.rerank(query, candidates, rerankStrategy, topK);
            return bridgeContext(results);
        } catch (Exception e) {
            log.error("混合检索加重排序失败", e);
            throw new RuntimeException("混合检索加重排序失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy) {
        return hybridSearchWithRerank(query, topK, rerankStrategy, DocumentSegmenter.StrategyType.CHAPTER);
    }

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                            Reranker.RerankStrategy rerankStrategy,
                                                            String userId,
                                                            String knowledgeBaseId) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", userId);
        filters.put("knowledgeBaseId", knowledgeBaseId);
        return hybridSearchWithRerank(query, topK, rerankStrategy, DocumentSegmenter.StrategyType.CHAPTER, filters);
    }

    // BM25 关键词召回：扫描全部分段/文档计算相关性得分，过滤后按分数降序返回
    private List<Map<String, Object>> bm25Search(String query, int topK, Map<String, Object> filters) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (bm25Index.isEmpty()) {
            rebuildBm25Index();
        }
        if (bm25Index.isEmpty()) {
            return results;
        }

        for (IndexedText indexed : bm25Index.values()) {
            if (!matchesFilters(indexed.metadata(), filters)) {
                continue;
            }
            float score = computeBM25(query, indexed.terms());
            if (score > 0) {
                results.add(Map.of("id", indexed.id(), "similarity", score));
            }
        }

        results.sort((a, b) -> Float.compare(
            ((Number) b.get("similarity")).floatValue(),
            ((Number) a.get("similarity")).floatValue()
        ));

        return results.subList(0, Math.min(topK, results.size()));
    }

    private List<Map<String, Object>> hybridSearchWithoutContext(String query, int topK,
                                                                   DocumentSegmenter.StrategyType strategyType,
                                                                   Map<String, Object> filters) {
        return hybridSearchInternal(query, topK, strategyType, filters, false);
    }

    private void indexText(String redisKey, String id, String content, Map<String, Object> metadata) {
        if (content == null || content.isBlank()) {
            return;
        }
        bm25Index.put(redisKey, new IndexedText(
                id,
                content.toLowerCase(Locale.ROOT).split("\\s+"),
                metadata == null ? Collections.emptyMap() : new HashMap<>(metadata)));
    }

    private void indexRedisText(String redisKey, Map<String, Object> metadata) {
        Object rawContent = redisTemplate.opsForValue().get(redisKey);
        if (rawContent == null) {
            return;
        }
        String id = redisKey.substring(redisKey.lastIndexOf(':') + 1);
        indexText(redisKey, id, rawContent.toString(), metadata);
    }

    // 简化 BM25 打分：统计查询词与文档词的词频贡献
    private float computeBM25(String query, String document) {
        if (query == null || document == null) {
            return 0.0f;
        }

        return computeBM25(query, document.toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private float computeBM25(String query, String[] docTerms) {
        if (query == null || docTerms == null) {
            return 0.0f;
        }

        String[] queryTerms = query.toLowerCase(Locale.ROOT).split("\\s+");

        int docLength = docTerms.length;
        if (docLength == 0) {
            return 0.0f;
        }

        float score = 0.0f;
        for (String term : queryTerms) {
            if (term.isEmpty()) continue;

            int termFreq = 0;
            for (String docTerm : docTerms) {
                if (docTerm.contains(term) || term.contains(docTerm)) {
                    termFreq++;
                }
            }

            if (termFreq > 0) {
                float tf = (float) termFreq / docLength;
                float bm25 = (float)(tf * (2.2 + 1) / (tf + 2.2));
                score += bm25;
            }
        }

        return score / queryTerms.length;
    }

    // 扫描并返回知识库中所有文档 ID
    public List<String> getAllDocumentIds() {
        try {
            List<String> keys = scanKeys(DOCUMENT_PREFIX + "*", 1000);
            if (keys == null) {
                return Collections.emptyList();
            }
            return keys.stream()
                .map(key -> key.substring(DOCUMENT_PREFIX.length()))
                .toList();
        } catch (Exception e) {
            log.warn("获取文档ID列表失败", e);
            return Collections.emptyList();
        }
    }

    public List<String> getAllDocumentIds(String userId, String knowledgeBaseId) {
        return getAllDocumentIds().stream()
                .filter(documentId -> matchesFilters(getDocumentMetadata(documentId), Map.of(
                        "userId", userId,
                        "knowledgeBaseId", knowledgeBaseId
                )))
                .toList();
    }

    public void updateDocument(String documentId, String content, Map<String, Object> metadata) {
        log.info("更新文档: documentId={}", documentId);
        removeDocument(documentId);
        addDocumentWithSegment(documentId, content, metadata, DocumentSegmenter.StrategyType.AUTO);
    }

    public void updateDocument(String documentId, String content, Map<String, Object> metadata,
                              DocumentSegmenter.StrategyType strategyType) {
        log.info("更新文档: documentId={}, strategy={}", documentId, strategyType);
        removeDocument(documentId);
        addDocumentWithSegment(documentId, content, metadata, strategyType);
    }

    public Map<String, Object> getStatistics() {
        return getStatistics(null, null);
    }

    // 知识库统计：统计指定范围内的文档数、分段数与总字符数
    public Map<String, Object> getStatistics(String userId, String knowledgeBaseId) {
        Map<String, Object> stats = new HashMap<>();

        List<String> docIds = (userId == null || knowledgeBaseId == null)
                ? getAllDocumentIds()
                : getAllDocumentIds(userId, knowledgeBaseId);
        stats.put("documentCount", docIds.size());

        int totalSegments = 0;
        for (String docId : docIds) {
            totalSegments += getSegmentIds(docId).size();
        }
        stats.put("segmentCount", totalSegments);

        long totalContentSize = 0;
        for (String docId : docIds) {
            String content = getDocumentContent(docId);
            if (content != null) {
                totalContentSize += content.length();
            }
        }
        stats.put("totalContentSize", totalContentSize);

        return stats;
    }

    // [NEW] ──────────── 查询扩展与上下文贯通 ────────────────────────

    /** 同义词典：中文常见同义词映射 */
    private static final Map<String, String> SYNONYM_MAP = Map.ofEntries(
            Map.entry("文档", "document 文件 资料"),
            Map.entry("AI", "人工智能 大模型 机器学习"),
            Map.entry("微服务", "分布式 服务拆分"),
            Map.entry("部署", "deploy 发布 上线"),
            Map.entry("配置", "config 设置 参数"),
            Map.entry("检索", "search 查询 搜索"),
            Map.entry("分段", "chunk 分块 切片"),
            Map.entry("向量", "embedding 嵌入"),
            Map.entry("RAG", "检索增强生成 知识库问答"),
            Map.entry("网关", "gateway 路由 代理")
    );

    /** 查询扩展：用同义词丰富查询文本，提高召回率 */
    private String expandQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        String lower = query.toLowerCase();
        StringBuilder expanded = new StringBuilder(query);
        boolean expanded_flag = false;
        for (Map.Entry<String, String> entry : SYNONYM_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) {
                expanded.append(" ").append(entry.getValue());
                expanded_flag = true;
            }
        }
        return expanded_flag ? expanded.toString() : null;
    }

    /**
     * 上下文贯通：为每个匹配到的分块拉取其前后相邻分块
     * 默认前后各拉 1 个相邻分块，形成连贯的上下文窗口
     */
    private List<Map<String, Object>> bridgeContext(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        int bridgeWindow = 1; // 前后各拉 1 个相邻分块
        Set<String> addedIds = new HashSet<>();
        List<Map<String, Object>> bridged = new ArrayList<>();

        for (Map<String, Object> result : results) {
            String id = (String) result.get("id");
            if (id != null) {
                addedIds.add(id);
            }
            bridged.add(result);
        }

        // 收集所有需要展开的相邻分块
        List<Map<String, Object>> neighborsToAdd = new ArrayList<>();
        for (Map<String, Object> result : results) {
            for (int offset = 1; offset <= bridgeWindow; offset++) {
                // 前向相邻
                String prevKey = "prevChunkId";
                addNeighbor(result, prevKey, addedIds, neighborsToAdd, offset == 1);
                // 后向相邻需要从已加入的邻居中继续展开
            }
        }

        // 后向相邻 — 从第一个匹配结果向后展开
        for (Map<String, Object> result : results) {
            String nextId = getChunkMetadata((String) result.get("id"), "nextChunkId");
            int steps = 0;
            while (nextId != null && steps < bridgeWindow) {
                if (addedIds.add(nextId)) {
                    String content = getSegmentContent(nextId);
                    if (content != null) {
                        Map<String, Object> neighbor = new HashMap<>();
                        neighbor.put("id", nextId);
                        neighbor.put("content", content);
                        neighbor.put("source", "bridged");
                        neighbor.put("similarity", 0.0);
                        neighborsToAdd.add(neighbor);
                    }
                }
                nextId = getChunkMetadata(nextId, "nextChunkId");
                steps++;
            }
        }

        bridged.addAll(neighborsToAdd);
        return bridged;
    }

    /** 从 Redis 读取分块元数据中的指定字段 */
    private String getChunkMetadata(String segmentId, String field) {
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(METADATA_PREFIX + segmentId);
            Object value = hash.get(field);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 拉取指定分块的前一个相邻分块 */
    private void addNeighbor(Map<String, Object> result, String field,
                             Set<String> addedIds, List<Map<String, Object>> out, boolean isFirstLevel) {
        String chunkId = getChunkMetadata((String) result.get("id"), field);
        if (chunkId != null && addedIds.add(chunkId)) {
            String content = getSegmentContent(chunkId);
            if (content != null) {
                Map<String, Object> neighbor = new HashMap<>();
                neighbor.put("id", chunkId);
                neighbor.put("content", content);
                neighbor.put("source", "bridged");
                neighbor.put("similarity", 0.0);
                out.add(neighbor);
            }
        }
    }

    // 归一化元数据：补全默认的 userId/knowledgeBaseId，用于多用户知识库隔离
    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> normalized = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        normalized.putIfAbsent("userId", "system");
        normalized.putIfAbsent("knowledgeBaseId", "default");
        return normalized;
    }

    // 用 SCAN 游标遍历匹配前缀的 Redis key，避免 KEYS 命令阻塞
    private List<String> scanKeys(String pattern, int count) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        try (var cursor = redisTemplate.getConnectionFactory().getConnection().scan(options)) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        return keys;
    }

    // 判断元数据是否命中所有过滤条件，用于多用户/知识库维度的隔离过滤
    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object expected = filter.getValue();
            if (expected == null || expected.toString().isBlank()) {
                continue;
            }
            Object actual = metadata.get(filter.getKey());
            if (actual == null || !expected.toString().equals(actual.toString())) {
                return false;
            }
        }
        return true;
    }

    // 读取分块在向量库中的元数据
    private Map<String, Object> vectorStoreMetadata(String id) {
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries("metadata:" + id);
            Map<String, Object> metadata = new HashMap<>();
            for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                metadata.put(entry.getKey().toString(), entry.getValue());
            }
            return metadata;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private record IndexedText(String id, String[] terms, Map<String, Object> metadata) {}
}
