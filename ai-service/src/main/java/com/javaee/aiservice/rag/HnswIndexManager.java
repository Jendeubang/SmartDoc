package com.javaee.aiservice.rag;

import com.github.jelmerk.knn.DistanceFunctions;
import com.github.jelmerk.knn.Item;
import com.github.jelmerk.knn.SearchResult;
import com.github.jelmerk.knn.hnsw.HnswIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 进程内 HNSW 近似近邻索引。
 *
 * Redis/Spring AI 向量库仍然是持久化和最终回退来源，本类只负责把向量加载到
 * Java 进程内存中进行低延迟召回。索引按 organizationId + knowledgeBaseId 隔离，
 * 查询结果在返回前还会经过 PermissionAwareRagService 的文档权限过滤。
 */
@Component
public class HnswIndexManager {

    private static final Logger log = LoggerFactory.getLogger(HnswIndexManager.class);
    private static final String RECORD_PREFIX = "hnsw:vector:";

    private final Map<String, ScopedIndex> indexes = new ConcurrentHashMap<>();
    private final Map<String, String> itemScopes = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${ai.vector.hnsw.enabled:true}")
    private boolean enabled;

    @Value("${ai.vector.hnsw.m:16}")
    private int m;

    @Value("${ai.vector.hnsw.ef-construction:200}")
    private int efConstruction;

    @Value("${ai.vector.hnsw.ef:128}")
    private int ef;

    @Value("${ai.vector.hnsw.max-items:200000}")
    private int maxItems;

    /** 生产环境使用 Spring 管理；单元测试使用该构造函数验证纯内存索引。 */
    public HnswIndexManager() {
    }

    HnswIndexManager(int m, int efConstruction, int ef, int maxItems) {
        this.enabled = true;
        this.m = m;
        this.efConstruction = efConstruction;
        this.ef = ef;
        this.maxItems = maxItems;
    }

    @PostConstruct
    void loadPersistedRecords() {
        if (!enabled || redisTemplate == null) {
            return;
        }
        try {
            int loaded = 0;
            for (String key : scanRecordKeys()) {
                Object raw = redisTemplate.opsForValue().get(key);
                PersistedVector record = parseRecord(raw, key);
                if (record != null && upsertInMemory(record.id(), record.vector(), record.metadata())) {
                    itemScopes.put(record.id(), scopeKey(record.metadata()));
                    loaded++;
                }
            }
            log.info("HNSW 持久化向量加载完成: records={}, scopes={}", loaded, indexes.size());
        } catch (Exception e) {
            // 不能因为内存索引加载失败阻止 AI 服务启动，查询会回退到 RedisVectorStore。
            log.warn("HNSW 持久化向量加载失败，将使用 Redis 向量检索回退", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isReady() {
        return enabled && ready.get();
    }

    /** 启动全量补建前暂时关闭 HNSW 查询，避免只加载了一半时造成召回下降。 */
    public void beginWarmup() {
        if (enabled) {
            ready.set(false);
        }
    }

    /** 全量补建完成后再打开 HNSW 查询。 */
    public void markReady() {
        if (enabled) {
            ready.set(true);
            log.info("HNSW 索引已就绪: scopes={}, items={}", scopeCount(), size());
        }
    }

    /**
     * 将一条向量写入内存索引，并保存到 Redis，供服务重启后恢复。
     * 维度不一致、空向量等异常只拒绝当前条目，不污染已有索引。
     */
    public boolean upsert(String id, float[] vector, Map<String, Object> metadata) {
        if (!validVector(id, vector)) {
            return false;
        }
        Map<String, Object> safeMetadata = copyMetadata(metadata);
        String scope = scopeKey(safeMetadata);
        String previousScope = itemScopes.get(id);
        if (previousScope != null && !previousScope.equals(scope)) {
            removeFromScope(previousScope, id);
        }

        if (!upsertInMemory(id, vector, safeMetadata)) {
            return false;
        }
        itemScopes.put(id, scope);
        persist(id, vector, safeMetadata);
        return true;
    }

    /** 删除向量，同时清理 Redis 中的 HNSW 快照。 */
    public boolean remove(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String scope = itemScopes.remove(id);
        boolean removed = scope != null && removeFromScope(scope, id);
        if (!removed) {
            // 兼容服务重启后旧索引未恢复 itemScopes 的场景。
            for (Map.Entry<String, ScopedIndex> entry : indexes.entrySet()) {
                removed |= removeFromScope(entry.getKey(), id);
            }
        }
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(RECORD_PREFIX + id);
            } catch (Exception e) {
                log.warn("删除 HNSW Redis 快照失败: id={}", id, e);
            }
        }
        return removed;
    }

    /**
     * 执行 HNSW 查询。返回 Optional.empty 表示索引尚未就绪，应由调用方回退到 Redis。
     * Optional.of(emptyList()) 表示索引已就绪但过滤后没有命中，不能误判为索引故障。
     */
    public Optional<List<Map<String, Object>>> search(float[] queryVector, int topK,
                                                       Map<String, Object> filters) {
        if (!isReady() || queryVector == null || queryVector.length == 0 || indexes.isEmpty()) {
            return Optional.empty();
        }
        int limit = Math.max(1, Math.min(topK, 50));
        int candidateLimit = Math.max(limit, Math.min(200, limit * 8));
        List<SearchHit> hits = new ArrayList<>();

        for (ScopedIndex scoped : indexes.values()) {
            scoped.lock.readLock().lock();
            try {
                if (scoped.index == null || scoped.index.size() == 0
                        || scoped.dimensions != queryVector.length) {
                    continue;
                }
                List<SearchResult<HnswVectorItem, Float>> nearest = scoped.index.findNearest(
                        queryVector, Math.min(candidateLimit, scoped.index.size()));
                for (SearchResult<HnswVectorItem, Float> result : nearest) {
                    HnswVectorItem item = result.item();
                    if (matchesFilters(item.metadata(), filters)) {
                        hits.add(new SearchHit(item, result.distance()));
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("HNSW 查询向量维度不匹配，触发 Redis 回退: dimension={}", queryVector.length);
                return Optional.empty();
            } finally {
                scoped.lock.readLock().unlock();
            }
        }

        hits.sort(Comparator.comparingDouble(hit -> hit.distance()));
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SearchHit hit : hits) {
            if (!seen.add(hit.item().id())) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>(hit.item().metadata());
            result.put("id", hit.item().id());
            result.put("similarity", hit.distance());
            results.add(result);
            if (results.size() >= limit) {
                break;
            }
        }
        return Optional.of(results);
    }

    public int size() {
        return indexes.values().stream().mapToInt(ScopedIndex::size).sum();
    }

    public int scopeCount() {
        return indexes.size();
    }

    /** 测试和启动补建使用：只清空 Java 内存，不删除 Redis 持久化快照。 */
    void clearInMemory() {
        indexes.clear();
        itemScopes.clear();
        ready.set(false);
    }

    boolean contains(String id) {
        return id != null && itemScopes.containsKey(id);
    }

    private boolean upsertInMemory(String id, float[] vector, Map<String, Object> metadata) {
        String scope = scopeKey(metadata);
        ScopedIndex scoped = indexes.computeIfAbsent(scope, ignored -> new ScopedIndex());
        scoped.lock.writeLock().lock();
        try {
            if (scoped.index == null) {
                scoped.dimensions = vector.length;
                scoped.index = newIndex(vector.length, maxItems);
            } else if (scoped.dimensions != vector.length) {
                log.warn("拒绝写入维度不一致的向量: id={}, expected={}, actual={}",
                        id, scoped.dimensions, vector.length);
                return false;
            }

            HnswVectorItem previous = scoped.items.remove(id);
            if (previous != null) {
                scoped.index.remove(id, previous.version());
            }
            if (scoped.index.size() >= Math.max(16, maxItems)) {
                log.warn("HNSW 索引达到容量上限，当前条目回退 Redis: scope={}, maxItems={}",
                        scope, maxItems);
                if (previous != null) {
                    scoped.index.add(previous);
                    scoped.items.put(id, previous);
                }
                return false;
            }
            HnswVectorItem item = new HnswVectorItem(id, vector.clone(), metadata);
            if (!scoped.index.add(item)) {
                if (previous != null) {
                    scoped.index.add(previous);
                    scoped.items.put(id, previous);
                }
                return false;
            }
            scoped.items.put(id, item);
            return true;
        } finally {
            scoped.lock.writeLock().unlock();
        }
    }

    private boolean removeFromScope(String scope, String id) {
        ScopedIndex scoped = indexes.get(scope);
        if (scoped == null) {
            return false;
        }
        scoped.lock.writeLock().lock();
        try {
            HnswVectorItem item = scoped.items.remove(id);
            if (item == null || scoped.index == null) {
                return false;
            }
            return scoped.index.remove(id, item.version());
        } finally {
            scoped.lock.writeLock().unlock();
        }
    }

    private HnswIndex<String, float[], HnswVectorItem, Float> newIndex(int dimensions, int capacity) {
        int safeCapacity = Math.max(16, capacity);
        int safeM = Math.max(4, m);
        int safeEfConstruction = Math.max(safeM, efConstruction);
        int safeEf = Math.max(1, ef);
        return HnswIndex.newBuilder(dimensions, DistanceFunctions.FLOAT_COSINE_DISTANCE, safeCapacity)
                .withM(safeM)
                .withEfConstruction(safeEfConstruction)
                .withEf(safeEf)
                .withRemoveEnabled()
                .build();
    }

    private void persist(String id, float[] vector, Map<String, Object> metadata) {
        if (redisTemplate == null) {
            return;
        }
        try {
            Map<String, Object> record = new HashMap<>();
            record.put("id", id);
            record.put("vector", vector);
            record.put("metadata", metadata);
            redisTemplate.opsForValue().set(RECORD_PREFIX + id, record);
        } catch (Exception e) {
            // RedisVectorStore 仍是主存储，HNSW 快照写失败时保留内存索引并让查询可回退。
            log.warn("写入 HNSW Redis 快照失败: id={}", id, e);
        }
    }

    private List<String> scanRecordKeys() {
        if (redisTemplate == null) {
            return Collections.emptyList();
        }
        Set<String> keys = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new LinkedHashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(RECORD_PREFIX + "*").count(1000).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return result;
        });
        return keys == null ? Collections.emptyList() : new ArrayList<>(keys);
    }

    private PersistedVector parseRecord(Object raw, String key) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String id = value(map.get("id"), key.substring(RECORD_PREFIX.length()));
        float[] vector = toFloatArray(map.get("vector"));
        Map<String, Object> metadata = toMetadata(map.get("metadata"));
        return validVector(id, vector) ? new PersistedVector(id, vector, metadata) : null;
    }

    private float[] toFloatArray(Object raw) {
        if (raw instanceof float[] values) {
            return values;
        }
        if (raw instanceof double[] values) {
            float[] result = new float[values.length];
            for (int i = 0; i < values.length; i++) result[i] = (float) values[i];
            return result;
        }
        if (raw instanceof Collection<?> values) {
            float[] result = new float[values.size()];
            int index = 0;
            for (Object value : values) {
                if (!(value instanceof Number number)) return null;
                result[index++] = number.floatValue();
            }
            return result;
        }
        return null;
    }

    private Map<String, Object> toMetadata(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) result.put(entry.getKey().toString(), entry.getValue());
        }
        return result;
    }

    private boolean validVector(String id, float[] vector) {
        if (!enabled || id == null || id.isBlank() || vector == null || vector.length == 0) return false;
        for (float value : vector) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return new HashMap<>();
        return new HashMap<>(metadata);
    }

    private String scopeKey(Map<String, Object> metadata) {
        String organizationId = value(metadata == null ? null : metadata.get("organizationId"), "global");
        String knowledgeBaseId = value(metadata == null ? null : metadata.get("knowledgeBaseId"), "default");
        return organizationId + "\u0000" + knowledgeBaseId;
    }

    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return true;
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            if (filter.getValue() == null) continue;
            Object actual = metadata.get(filter.getKey());
            if (actual == null || !filter.getValue().toString().equals(actual.toString())) return false;
        }
        return true;
    }

    private String value(Object raw, String fallback) {
        return raw == null || raw.toString().isBlank() ? fallback : raw.toString();
    }

    private static final class ScopedIndex {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final Map<String, HnswVectorItem> items = new HashMap<>();
        private HnswIndex<String, float[], HnswVectorItem, Float> index;
        private int dimensions;

        int size() {
            lock.readLock().lock();
            try {
                return index == null ? 0 : index.size();
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    private record PersistedVector(String id, float[] vector, Map<String, Object> metadata) {
    }

    private record SearchHit(HnswVectorItem item, Float distance) {
    }

    private record HnswVectorItem(String id, float[] vector, Map<String, Object> metadata)
            implements Item<String, float[]> {
        private HnswVectorItem {
            vector = vector.clone();
            metadata = metadata == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new HashMap<>(metadata));
        }

        @Override
        public int dimensions() {
            return vector.length;
        }
    }
}
