package com.javaee.aiservice.conversation;

/**
 * 【简历：多模态对话管理】
 * 管理用户与 AI 的多轮对话上下文，支持会话生命周期、
 * 上下文窗口管理和历史消息存储。
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;
import com.javaee.common.config.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话管理器
 * 管理对话生命周期和消息历史
 * 支持多轮对话和上下文管理
 */
// 类职责：会话管理统一入口，对应简历第4条——统一管理 Redis 内存会话与 MySQL 持久化会话。
@Component
public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);
    private static final String CONVERSATION_PREFIX = "conv:";
    private static final String USER_PREFIX = "user:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ConversationPersistenceService persistence;

    @Value("${ai.conversation.max-messages:100}")
    private int maxMessages;

    @Value("${ai.conversation.expiry-hours:24}")
    private int expiryHours;

    /**
     * 创建新对话
     * @param userId 用户ID
     * @return 对话ID
     */
    public String createConversation(String userId) {
        return createConversation(userId, "general");
    }

    // 创建会话：写入 Redis 缓存（带过期时间）并同步持久化到 MySQL。
    public String createConversation(String userId, String channel) {
        String conversationId = UUID.randomUUID().toString();
        String key = CONVERSATION_PREFIX + conversationId;

        Map<String, Object> conversation = new HashMap<>();
        conversation.put("userId", userId);
        conversation.put("organizationId", TenantContext.get() == null ? "personal" : TenantContext.get());
        conversation.put("createdAt", System.currentTimeMillis());
        conversation.put("updatedAt", System.currentTimeMillis());
        conversation.put("messages", new ArrayList<String>());

        redisTemplate.opsForHash().putAll(key, conversation);
        redisTemplate.expire(key, java.time.Duration.ofHours(expiryHours));
        persistence.create(conversationId, userId, channel);
        persistence.attachOrganization(conversationId, userId, TenantContext.get());

        log.info("创建新对话: userId={}, conversationId={}", userId, conversationId);
        return conversationId;
    }

    /**
     * 添加消息到对话
     * @param conversationId 对话ID
     * @param userMessage 用户消息
     * @param assistantMessage 助手消息
     */
    // 在 Redis 中追加一轮问答，并裁剪超出 maxMessages 上限的历史。
    public void addMessage(String conversationId, String userMessage, String assistantMessage) {
        String key = CONVERSATION_PREFIX + conversationId;
        
        @SuppressWarnings("unchecked")
        List<String> messages = (List<String>) redisTemplate.opsForHash().get(key, "messages");
        if (messages == null) {
            messages = new ArrayList<>();
        }

        messages.add("User: " + userMessage);
        messages.add("Assistant: " + assistantMessage);

        if (messages.size() > maxMessages) {
            messages = messages.subList(messages.size() - maxMessages, messages.size());
        }

        redisTemplate.opsForHash().put(key, "messages", messages);
        redisTemplate.opsForHash().put(key, "updatedAt", System.currentTimeMillis());
        redisTemplate.expire(key, java.time.Duration.ofHours(expiryHours));

        log.debug("添加消息到对话: conversationId={}, messageCount={}", conversationId, messages.size());
    }

    /**
     * 获取对话历史
     * @param conversationId 对话ID
     * @return 消息列表
     */
    public List<String> getConversationHistory(String conversationId) {
        String key = CONVERSATION_PREFIX + conversationId;
        
        @SuppressWarnings("unchecked")
        List<String> messages = (List<String>) redisTemplate.opsForHash().get(key, "messages");
        return messages != null ? messages : Collections.emptyList();
    }

    /**
     * 删除对话
     * @param conversationId 对话ID
     */
    public void deleteConversation(String conversationId) {
        String key = CONVERSATION_PREFIX + conversationId;
        redisTemplate.delete(key);
        log.info("删除对话: conversationId={}", conversationId);
    }

    // 校验归属后同时写入 Redis 与 MySQL。
    public void addMessageForUser(String conversationId, String userId, String userMessage, String assistantMessage) {
        assertOwner(conversationId, userId);
        addMessage(conversationId, userMessage, assistantMessage);
        persistence.appendPair(conversationId, userId, userMessage, assistantMessage);
    }

    public void ensurePersistentConversation(String conversationId, String userId, String channel) {
        assertOwner(conversationId, userId);
        persistence.create(conversationId, userId, channel);
    }

    // 优先从 MySQL 恢复历史，否则回退到 Redis 内存历史。
    public List<String> getConversationHistoryForUser(String conversationId, String userId) {
        if (persistence.isOwner(conversationId, userId)) {
            return persistence.legacyMessages(conversationId, userId);
        }
        assertOwner(conversationId, userId);
        return getConversationHistory(conversationId);
    }

    public void deleteConversationForUser(String conversationId, String userId) {
        assertOwner(conversationId, userId);
        deleteConversation(conversationId);
    }

    // 校验会话归属；若 Redis 无缓存但 MySQL 存在记录，则重建 Redis 会话。
    public void assertOwner(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("对话ID不能为空");
        }
        String key = CONVERSATION_PREFIX + conversationId;
        Object owner = redisTemplate.opsForHash().get(key, "userId");
        if (owner == null) {
            if (persistence.isOwner(conversationId, userId)) {
                restoreRedisConversation(conversationId, userId);
                return;
            }
            throw new IllegalArgumentException("对话不存在");
        }
        if (!String.valueOf(owner).equals(userId)) {
            throw new SecurityException("无权访问该对话");
        }
    }

    // 从 MySQL 历史消息重建 Redis 会话缓存（缓存过期或重启后的恢复）。
    private void restoreRedisConversation(String conversationId, String userId) {
        String key = CONVERSATION_PREFIX + conversationId;
        List<String> history = new ArrayList<>(persistence.legacyMessages(conversationId, userId));
        if (history.size() > maxMessages) history = new ArrayList<>(history.subList(history.size() - maxMessages, history.size()));
        Map<String, Object> conversation = new HashMap<>();
        conversation.put("userId", userId);
        conversation.put("createdAt", System.currentTimeMillis());
        conversation.put("updatedAt", System.currentTimeMillis());
        conversation.put("messages", history);
        redisTemplate.opsForHash().putAll(key, conversation);
        redisTemplate.expire(key, java.time.Duration.ofHours(expiryHours));
    }

    /**
     * 获取用户的所有对话
     * @param userId 用户ID
     * @return 对话ID列表
     */
    // 通过 Redis SCAN 扫描所有会话键，列出属于该用户的会话 ID。
    public List<String> getUserConversations(String userId) {
        try {
            List<String> conversationIds = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().match(CONVERSATION_PREFIX + "*").count(200).build();
            try (var cursor = redisTemplate.getConnectionFactory().getConnection().scan(options)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next(), StandardCharsets.UTF_8);
                    String convUserId = (String) redisTemplate.opsForHash().get(key, "userId");
                if (userId.equals(convUserId)) {
                    conversationIds.add(key.substring(CONVERSATION_PREFIX.length()));
                }
            }
            }
            return conversationIds;
        } catch (Exception e) {
            log.warn("获取用户对话列表失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取对话信息
     * @param conversationId 对话ID
     * @return 对话信息
     */
    public Map<String, Object> getConversationInfo(String conversationId) {
        String key = CONVERSATION_PREFIX + conversationId;
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        
        Map<String, Object> info = new HashMap<>();
        for (Map.Entry<Object, Object> entry : hash.entrySet()) {
            info.put(entry.getKey().toString(), entry.getValue());
        }
        return info;
    }
}
