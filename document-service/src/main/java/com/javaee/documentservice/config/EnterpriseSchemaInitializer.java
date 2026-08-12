package com.javaee.documentservice.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class EnterpriseSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    @Value("${document.schema.auto-migration.enabled:true}")
    private boolean enabled;

    public EnterpriseSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS enterprise_organization (
              id VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL,
              owner_user_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'active',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_enterprise_owner(owner_user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS enterprise_department (
              id VARCHAR(64) PRIMARY KEY, organization_id VARCHAR(64) NOT NULL, name VARCHAR(80) NOT NULL,
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_enterprise_department(organization_id, name), INDEX idx_department_org(organization_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS enterprise_member (
              id VARCHAR(64) PRIMARY KEY, organization_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL,
              department_id VARCHAR(64), role VARCHAR(32) NOT NULL DEFAULT 'member', status VARCHAR(20) NOT NULL DEFAULT 'active',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              UNIQUE KEY uk_enterprise_member(organization_id, user_id), INDEX idx_member_user(user_id), INDEX idx_member_org(organization_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS enterprise_folder (
              id VARCHAR(64) PRIMARY KEY, organization_id VARCHAR(64) NOT NULL, department_id VARCHAR(64), parent_id VARCHAR(64),
              name VARCHAR(100) NOT NULL, visibility VARCHAR(20) NOT NULL DEFAULT 'organization',
              write_role VARCHAR(32) NOT NULL DEFAULT 'member', created_by BIGINT NOT NULL,
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_folder_org(organization_id), INDEX idx_folder_parent(parent_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS document_approval (
              id VARCHAR(64) PRIMARY KEY, organization_id VARCHAR(64), document_id VARCHAR(64) NOT NULL,
              approval_type VARCHAR(40) NOT NULL, applicant_user_id BIGINT NOT NULL, approver_user_id BIGINT NOT NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'pending', comment VARCHAR(500),
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP, decision_time DATETIME,
              INDEX idx_approval_approver(approver_user_id, status), INDEX idx_approval_doc(document_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS enterprise_notification (
              id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, type VARCHAR(40) NOT NULL,
              title VARCHAR(160) NOT NULL, content VARCHAR(800), related_id VARCHAR(64), is_read TINYINT(1) NOT NULL DEFAULT 0,
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP, INDEX idx_notification_user(user_id, is_read, create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS enterprise_audit_log (
              id VARCHAR(64) PRIMARY KEY, organization_id VARCHAR(64), user_id BIGINT NOT NULL,
              action VARCHAR(60) NOT NULL, target_type VARCHAR(40) NOT NULL, target_id VARCHAR(64), detail VARCHAR(1000),
              ip_address VARCHAR(64), create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
              INDEX idx_audit_org(organization_id, create_time), INDEX idx_audit_user(user_id, create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS document_secure_share (
              id VARCHAR(64) PRIMARY KEY, token_hash CHAR(64) NOT NULL UNIQUE, document_id VARCHAR(64) NOT NULL,
              created_by BIGINT NOT NULL, password_hash VARCHAR(100), expires_at DATETIME, max_access INT NOT NULL DEFAULT 0,
              access_count INT NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'active',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP, INDEX idx_share_doc(document_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        addDocumentColumn("organization_id", "VARCHAR(64) NULL");
        addDocumentColumn("department_id", "VARCHAR(64) NULL");
        addDocumentColumn("folder_id", "VARCHAR(64) NULL");
    }

    private void addDocumentColumn(String column, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='document' AND COLUMN_NAME=?", Integer.class, column);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE document ADD COLUMN " + column + " " + definition);
    }
}
