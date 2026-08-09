package com.javaee.documentservice.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class ParagraphLockService {
    private static final String PREFIX = "smartdoc:paragraph-lock:";
    private static final long TTL_SECONDS = 120;
    private final RedisTemplate<String, Object> redisTemplate;

    public ParagraphLockService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Map<String, Object> acquire(String documentId, String paragraphId, Long userId) {
        String key = key(documentId, paragraphId);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(userId), TTL_SECONDS, TimeUnit.SECONDS);
        Object holder = redisTemplate.opsForValue().get(key);
        boolean mine = String.valueOf(userId).equals(String.valueOf(holder));
        if (mine) redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
        return lockState(paragraphId, holder, Boolean.TRUE.equals(acquired), mine);
    }

    public Map<String, Object> release(String documentId, String paragraphId, Long userId) {
        String key = key(documentId, paragraphId);
        Object holder = redisTemplate.opsForValue().get(key);
        if (String.valueOf(userId).equals(String.valueOf(holder))) redisTemplate.delete(key);
        return Map.of("paragraphId", paragraphId, "released", true);
    }

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

    private Map<String, Object> lockState(String paragraphId, Object holder, boolean acquired, boolean mine) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("paragraphId", paragraphId);
        state.put("ownerUserId", holder == null ? null : String.valueOf(holder));
        state.put("acquired", acquired);
        state.put("mine", mine);
        state.put("ttlSeconds", TTL_SECONDS);
        return state;
    }

    private String key(String documentId, String paragraphId) {
        return PREFIX + documentId + ":" + paragraphId;
    }
}