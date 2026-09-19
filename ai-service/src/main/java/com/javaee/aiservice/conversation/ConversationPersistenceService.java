package com.javaee.aiservice.conversation;

import com.javaee.common.config.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

// 类职责：对话持久化服务，对应简历第4条「MySQL 持久化对话与消息」——
// 将会话写入 ai_conversation、消息写入 ai_conversation_message，并支持历史恢复与软删除。
@Service
public class ConversationPersistenceService {
    private final JdbcTemplate jdbc;

    public ConversationPersistenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String conversationId, String userId) {
        create(conversationId, userId, "general");
    }

    // 幂等创建会话记录（INSERT IGNORE），并写入当前租户的组织 ID。
    public void create(String conversationId, String userId, String channel) {
        String value = channel == null || channel.isBlank() ? "general" : channel.trim();
        jdbc.update("INSERT IGNORE INTO ai_conversation(id,user_id,channel,organization_id,title) VALUES(?,?,?,?,'新对话')",
                conversationId, userId, value, TenantContext.get());
    }

    public void attachOrganization(String conversationId, String userId, String organizationId) {
        jdbc.update("UPDATE ai_conversation SET organization_id=? WHERE id=? AND user_id=? AND status='active' " +
                        "AND (organization_id IS NULL OR organization_id=?)",
                organizationId, conversationId, userId, organizationId);
    }

    public boolean isOwner(String conversationId, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE id=? AND user_id=? AND status='active' " + tenantPredicate(),
                Integer.class, tenantArgs(conversationId, userId));
        return count != null && count > 0;
    }

    // 事务内持久化一轮问答：确保会话存在并校验归属后，写入用户消息与助手消息，同时更新标题与时间。
    @Transactional
    public void appendPair(String conversationId, String userId, String userMessage, String assistantMessage) {
        create(conversationId, userId);
        if (!isOwner(conversationId, userId)) throw new SecurityException("无权访问该对话");
        String organizationId = jdbc.queryForObject(
                "SELECT organization_id FROM ai_conversation WHERE id=? AND user_id=?",
                String.class, conversationId, userId);
        String title = titleFrom(userMessage);
        jdbc.update("UPDATE ai_conversation SET title=IF(title='新对话',?,title),update_time=NOW() WHERE id=? AND user_id=? AND status='active'",
                title, conversationId, userId);
        jdbc.update("INSERT INTO ai_conversation_message(conversation_id,user_id,organization_id,role,content) VALUES(?,?,?, 'user', ?)",
                conversationId, userId, organizationId, userMessage);
        jdbc.update("INSERT INTO ai_conversation_message(conversation_id,user_id,organization_id,role,content) VALUES(?,?,?, 'ai', ?)",
                conversationId, userId, organizationId, assistantMessage);
    }

    // 查询用户会话列表：包含消息数、最后一条消息，按更新时间倒序。
    public List<Map<String, Object>> list(String userId, String channel) {
        String sql = """
            SELECT c.id,c.title,c.create_time,c.update_time,
                   COUNT(m.id) AS message_count,
                   (SELECT mm.content FROM ai_conversation_message mm
                    WHERE mm.conversation_id=c.id ORDER BY mm.id DESC LIMIT 1) AS last_message
            FROM ai_conversation c
            LEFT JOIN ai_conversation_message m ON m.conversation_id=c.id
            WHERE c.user_id=? AND c.channel=? AND c.status='active'
            """ + tenantPredicate("c") + """
            GROUP BY c.id,c.title,c.create_time,c.update_time
            ORDER BY c.update_time DESC
            LIMIT 100
            """;
        return jdbc.queryForList(sql, tenantArgs(userId, channel));
    }

    // 按 id 升序读取会话全部消息，用于历史恢复。
    public List<Map<String, Object>> messages(String conversationId, String userId) {
        if (!isOwner(conversationId, userId)) throw new SecurityException("无权访问该对话");
        String sql = """
            SELECT id,role,content,create_time
            FROM ai_conversation_message
            WHERE conversation_id=? AND user_id=?
            """ + tenantPredicate() + """
            ORDER BY id ASC
            """;
        return jdbc.queryForList(sql, tenantArgs(conversationId, userId));
    }

    // 将消息格式化为 "User:" / "Assistant:" 前缀的文本，供 Redis 内存会话恢复。
    public List<String> legacyMessages(String conversationId, String userId) {
        return messages(conversationId, userId).stream()
                .map(item -> ("user".equals(item.get("role")) ? "User: " : "Assistant: ") + item.get("content"))
                .toList();
    }

    // 重命名会话标题，长度限制 120 字符。
    public void rename(String conversationId, String userId, String title) {
        String value = title == null ? "" : title.trim();
        if (value.isBlank()) throw new IllegalArgumentException("会话标题不能为空");
        if (value.length() > 120) value = value.substring(0, 120);
        String sql = "UPDATE ai_conversation SET title=?,update_time=NOW() WHERE id=? AND user_id=? AND status='active' " + tenantPredicate();
        int changed = jdbc.update(sql, tenantArgs(value, conversationId, userId));
        if (changed == 0) throw new SecurityException("无权访问该对话");
    }

    // 软删除会话：将状态置为 deleted，而非物理删除。
    public void delete(String conversationId, String userId) {
        String sql = "UPDATE ai_conversation SET status='deleted',update_time=NOW() WHERE id=? AND user_id=? AND status='active' " + tenantPredicate();
        int changed = jdbc.update(sql, tenantArgs(conversationId, userId));
        if (changed == 0) throw new SecurityException("无权访问该对话");
    }

    private String titleFrom(String message) {
        String normalized = message == null ? "新对话" : message.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) return "新对话";
        return normalized.length() > 28 ? normalized.substring(0, 28) + "…" : normalized;
    }

    private String tenantPredicate() { return tenantPredicate(""); }

    // 多租户隔离：按当前租户的 organization_id 拼接 SQL 过滤条件。
    private String tenantPredicate(String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return TenantContext.get() == null
                ? " AND " + prefix + "organization_id IS NULL "
                : " AND " + prefix + "organization_id=? ";
    }

    private Object[] tenantArgs(Object... base) {
        if (TenantContext.get() == null) return base;
        Object[] args = java.util.Arrays.copyOf(base, base.length + 1);
        args[base.length] = TenantContext.get();
        return args;
    }
}
