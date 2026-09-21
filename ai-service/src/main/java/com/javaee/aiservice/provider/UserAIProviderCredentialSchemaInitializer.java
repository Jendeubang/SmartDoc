package com.javaee.aiservice.provider;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等创建用户模型密钥表，避免旧环境必须手工执行 SQL。 */
@Component
public class UserAIProviderCredentialSchemaInitializer {
    private final JdbcTemplate jdbc;

    public UserAIProviderCredentialSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_provider_credential (
                  id VARCHAR(64) NOT NULL PRIMARY KEY,
                  user_id VARCHAR(64) NOT NULL,
                  organization_id VARCHAR(64) NULL,
                  provider VARCHAR(32) NOT NULL,
                  model_name VARCHAR(128) NOT NULL,
                  base_url VARCHAR(255) NOT NULL,
                  api_key_ciphertext TEXT NOT NULL,
                  api_key_hint VARCHAR(32) NOT NULL,
                  is_default TINYINT(1) NOT NULL DEFAULT 0,
                  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX idx_ai_provider_credential_scope (organization_id, user_id, status, is_default, updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }
}
