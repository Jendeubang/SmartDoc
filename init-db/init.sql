-- ==========================================================
-- DocAI 数据库初始化脚本
-- 用于 docker compose 首次启动时自动建库、建表、插入初始数据
-- ==========================================================

CREATE DATABASE IF NOT EXISTS `doc_ai`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `doc_ai`;

-- ── 用户表 ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名',
  `password` VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密）',
  `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
  `phone` VARCHAR(20) NOT NULL COMMENT '手机号',
  `role` VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '角色',
  `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '状态（0:禁用,1:启用）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 插入初始用户（密码均为 123456 的 BCrypt 哈希）
INSERT INTO `user` (`username`, `password`, `email`, `phone`, `role`, `status`) VALUES
('admin',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'admin@docai.com', '13900000001', 'ADMIN', 1),
('user',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'user@docai.com',  '13900000002', 'USER',  1)
ON DUPLICATE KEY UPDATE `username` = VALUES(`username`);

-- ── 文件元数据表 ────────────────────────────────────
CREATE TABLE IF NOT EXISTS `file_metadata` (
  `id` VARCHAR(64) NOT NULL,
  `file_id` VARCHAR(64) DEFAULT NULL,
  `file_name` VARCHAR(255) DEFAULT NULL,
  `original_file_name` VARCHAR(255) DEFAULT NULL,
  `file_path` VARCHAR(1024) DEFAULT NULL,
  `file_type` VARCHAR(128) DEFAULT NULL,
  `file_size` BIGINT DEFAULT 0,
  `md5` VARCHAR(64) DEFAULT NULL,
  `storage_type` VARCHAR(32) DEFAULT NULL,
  `bucket_name` VARCHAR(128) DEFAULT NULL,
  `object_key` VARCHAR(1024) DEFAULT NULL,
  `status` VARCHAR(32) DEFAULT NULL,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_file_id` (`file_id`),
  INDEX `idx_bucket_name` (`bucket_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 文档表 ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS `document` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `title` VARCHAR(255) NOT NULL,
  `content` TEXT,
  `summary` TEXT,
  `keywords` TEXT,
  `file_id` VARCHAR(64),
  `user_id` BIGINT,
  `bucket_name` VARCHAR(128),
  `object_name` VARCHAR(512),
  `status` VARCHAR(20) DEFAULT 'active',
  `version` INT DEFAULT 1,
  `category` VARCHAR(50),
  `tags` TEXT,
  `created_by` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 文档协作权限表 ──────────────────────────────────
CREATE TABLE IF NOT EXISTS `document_category` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `user_id` BIGINT NOT NULL,
  `name` VARCHAR(50) NOT NULL,
  `color` VARCHAR(16) NOT NULL DEFAULT '#ACA0CE',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_user_category_name` (`user_id`, `name`),
  INDEX `idx_category_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `document_access` (
  `id` VARCHAR(64) NOT NULL PRIMARY KEY,
  `document_id` VARCHAR(64) NOT NULL,
  `bucket_name` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `role` VARCHAR(20) NOT NULL DEFAULT 'editor',
  `expires_at` DATETIME DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_document_user` (`document_id`, `user_id`),
  INDEX `idx_user_bucket` (`user_id`, `bucket_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 审计日志表（AIOps 用） ──────────────────────────
CREATE TABLE IF NOT EXISTS `mcp_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `skill_name` VARCHAR(100),
  `params` TEXT,
  `result` TEXT,
  `status` VARCHAR(20),
  `user_id` BIGINT,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_skill_name` (`skill_name`),
  INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
