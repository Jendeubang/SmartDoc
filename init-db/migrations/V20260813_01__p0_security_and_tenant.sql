-- SmartDoc P0 idempotent migration for existing installations.
-- The migration runner records this file after successful execution.

ALTER TABLE `mcp_audit_log` ADD COLUMN IF NOT EXISTS `trace_id` VARCHAR(64) NULL;
ALTER TABLE `mcp_audit_log` ADD COLUMN IF NOT EXISTS `action` VARCHAR(128) NULL;
ALTER TABLE `mcp_audit_log` ADD COLUMN IF NOT EXISTS `params` TEXT NULL;
ALTER TABLE `mcp_audit_log` ADD COLUMN IF NOT EXISTS `result` TEXT NULL;
ALTER TABLE `mcp_audit_log` ADD COLUMN IF NOT EXISTS `status` VARCHAR(20) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE `mcp_audit_log` ADD COLUMN IF NOT EXISTS `user_id` VARCHAR(64) NULL;
ALTER TABLE `mcp_audit_log` ADD COLUMN IF NOT EXISTS `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE `file_metadata` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document` ADD COLUMN IF NOT EXISTS `department_id` VARCHAR(64) NULL;
ALTER TABLE `document` ADD COLUMN IF NOT EXISTS `folder_id` VARCHAR(64) NULL;
ALTER TABLE `document` ADD COLUMN IF NOT EXISTS `enterprise_access_level` VARCHAR(20) NOT NULL DEFAULT 'edit';
ALTER TABLE `document_category` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document_access` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;

CREATE TABLE IF NOT EXISTS `ai_conversation` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `user_id` VARCHAR(64) NOT NULL,
  `channel` VARCHAR(32) NOT NULL DEFAULT 'general',
  `organization_id` VARCHAR(64) NULL,
  `title` VARCHAR(120) NOT NULL DEFAULT 'New conversation',
  `status` VARCHAR(20) NOT NULL DEFAULT 'active',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_ai_conversation_tenant` (`organization_id`,`user_id`,`channel`,`status`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_conversation_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `conversation_id` VARCHAR(64) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `organization_id` VARCHAR(64) NULL,
  `role` VARCHAR(16) NOT NULL,
  `content` LONGTEXT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_ai_message_tenant` (`organization_id`,`user_id`,`conversation_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

UPDATE `mcp_audit_log` SET `action`='legacy' WHERE `action` IS NULL OR `action`='';
