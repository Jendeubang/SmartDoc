package com.javaee.aiservice.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 类职责：会话表自动建表与迁移，对应简历第4条 MySQL 持久化——
// 应用启动时确保 ai_conversation / ai_conversation_message 表及索引就绪。
@Component
public class ConversationSchemaInitializer {
    private static final Logger log = LoggerFactory.getLogger(ConversationSchemaInitializer.class);
    private final JdbcTemplate jdbc;

    public ConversationSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 应用就绪后建表，并幂等地补充字段与索引。
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_conversation (
              id VARCHAR(64) NOT NULL PRIMARY KEY,
              user_id VARCHAR(64) NOT NULL,
              channel VARCHAR(32) NOT NULL DEFAULT 'general',
              organization_id VARCHAR(64) NULL,
              title VARCHAR(120) NOT NULL DEFAULT '新对话',
              status VARCHAR(20) NOT NULL DEFAULT 'active',
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_ai_conversation_user (user_id, channel, status, update_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_conversation_message (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              conversation_id VARCHAR(64) NOT NULL,
              user_id VARCHAR(64) NOT NULL,
              organization_id VARCHAR(64) NULL,
              role VARCHAR(16) NOT NULL,
              content LONGTEXT NOT NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              INDEX idx_ai_message_conversation (conversation_id, id),
              INDEX idx_ai_message_user (user_id, create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        try {
            jdbc.execute("ALTER TABLE ai_conversation ADD COLUMN channel VARCHAR(32) NOT NULL DEFAULT 'general' AFTER user_id");
        } catch (Exception ignored) {
            // 已存在该字段时无需迁移。
        }
        addColumn("ai_conversation", "organization_id", "VARCHAR(64) NULL AFTER channel");
        addColumn("ai_conversation_message", "organization_id", "VARCHAR(64) NULL AFTER user_id");
        addIndex("ai_conversation", "idx_ai_conversation_tenant", "(`organization_id`,`user_id`,`channel`,`status`,`update_time`)");
        addIndex("ai_conversation_message", "idx_ai_message_tenant", "(`organization_id`,`user_id`,`conversation_id`,`id`)");
        log.info("AI 对话持久化表已就绪");
    }

    // 检查 information_schema，若列不存在则 ALTER 补加（幂等迁移）。
    private void addColumn(String table, String column, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?", Integer.class, table, column);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
    }

    // 检查 information_schema，若索引不存在则 ALTER 补建（幂等迁移）。
    private void addIndex(String table, String index, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?", Integer.class, table, index);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE `" + table + "` ADD INDEX `" + index + "` " + definition);
    }
}
