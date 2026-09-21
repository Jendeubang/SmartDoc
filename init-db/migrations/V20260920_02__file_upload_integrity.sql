-- File upload integrity: distinguish complete files from compensating uploads.
UPDATE `file_metadata`
SET `status` = 'ACTIVE'
WHERE `status` IS NULL OR `status` = '';

ALTER TABLE `file_metadata`
    MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'READY';

ALTER TABLE `file_metadata`
    ADD INDEX `idx_file_dedupe` (`bucket_name`, `organization_id`, `create_by`, `md5`, `status`);
