package com.javaee.aiservice.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MCP 数据库表自动初始化
 */
@Component
public class McpSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(McpSchemaInitializer.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        String sql = """
            CREATE TABLE IF NOT EXISTS `mcp_audit_log` (
              `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              `trace_id` VARCHAR(64) COMMENT '追踪ID',
              `user_id` VARCHAR(64) COMMENT '用户ID',
              `action` VARCHAR(128) NOT NULL COMMENT '操作名称',
              `params` TEXT COMMENT '操作参数(JSON)',
              `result` TEXT COMMENT '操作结果(JSON)',
              `status` VARCHAR(20) DEFAULT 'SUCCESS' COMMENT '状态: SUCCESS/FAIL/DENIED',
              `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
              INDEX `idx_user_id` (`user_id`),
              INDEX `idx_action` (`action`),
              INDEX `idx_create_time` (`create_time`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP审计日志'
            """;
        try {
            jdbcTemplate.execute(sql);
            log.info("MCP 审计日志表已就绪");
        } catch (Exception e) {
            log.error("MCP 审计日志表创建失败", e);
        }
    }
}
