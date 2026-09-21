package com.javaee.documentservice.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// 段落锁服务：基于 Redis 实现段落级互斥锁，用于多人并发编辑时锁定同一段落。
// 对应简历第 6 条「企业空间与在线协同」中的段落锁。
@Service
public class ParagraphLockService {
    private static final String PREFIX = "smartdoc:paragraph-lock:";
    private static final long TTL_SECONDS = 120;
    private final RedisTemplate<String, Object> redisTemplate;

    public ParagraphLockService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 获取段落锁：Redis setIfAbsent 抢占，持有者可续期（TTL 120 秒）
    public Map<String, Object> acquire(String documentId, String paragraphId, Long userId) {
        String key = key(documentId, paragraphId);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(userId), TTL_SECONDS, TimeUnit.SECONDS);
        Object holder = redisTemplate.opsForValue().get(key);
        boolean mine = String.valueOf(userId).equals(String.valueOf(holder));
        if (mine) redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
        return lockState(paragraphId, holder, Boolean.TRUE.equals(acquired), mine);
    }

    // 释放段落锁：仅持有者本人可释放
    public Map<String, Object> release(String documentId, String paragraphId, Long userId) {
        String key = key(documentId, paragraphId);
        Object holder = redisTemplate.opsForValue().get(key);
        if (String.valueOf(userId).equals(String.valueOf(holder))) redisTemplate.delete(key);
        return Map.of("paragraphId", paragraphId, "released", true);
    }

    // 列出文档所有段落锁及持有者
    public Map<String, Object> list(String documentId, Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> keys = redisTemplate.keys(PREFIX + documentId + ":*");
        if (keys == null) return result;
        for (String key : keys) {
            Object holder = redisTemplate.opsForValue().get(key);
            if (holder == null) continue;
            String paragraphId = key.substring((PREFIX + documentId + ":").length());
            result.put(paragraphId, lockState(paragraphId, holder, false, String.valueOf(userId).equals(String.valueOf(holder))));
        }
        return result;
    }

    // 释放某用户在文档上持有的全部段落锁
    public void releaseAllByUser(String documentId, Long userId) {
        Set<String> keys = redisTemplate.keys(PREFIX + documentId + ":*");
        if (keys == null) return;
        for (String key : keys) {
            Object holder = redisTemplate.opsForValue().get(key);
            if (String.valueOf(userId).equals(String.valueOf(holder))) redisTemplate.delete(key);
        }
    }

    // 组装段落锁状态信息
    private Map<String, Object> lockState(String paragraphId, Object holder, boolean acquired, boolean mine) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("paragraphId", paragraphId);
        state.put("ownerUserId", holder == null ? null : String.valueOf(holder));
        state.put("acquired", acquired);
        state.put("mine", mine);
        state.put("ttlSeconds", TTL_SECONDS);
        return state;
    }

    // 生成段落锁在 Redis 中的键
    private String key(String documentId, String paragraphId) {
        return PREFIX + documentId + ":" + paragraphId;
    }
}
