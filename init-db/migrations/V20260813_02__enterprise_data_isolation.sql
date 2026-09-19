-- Add tenant dimensions to enterprise-owned document data.
ALTER TABLE `document_category` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document_version` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document_access` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document_comment` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document_annotation` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document_suggestion` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `enterprise_notification` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;
ALTER TABLE `document_secure_share` ADD COLUMN IF NOT EXISTS `organization_id` VARCHAR(64) NULL;

UPDATE `document_version` child JOIN `document` parent ON parent.id=child.document_id
SET child.organization_id=parent.organization_id
WHERE child.organization_id IS NULL AND parent.organization_id IS NOT NULL;
UPDATE `document_access` child JOIN `document` parent ON parent.id=child.document_id
SET child.organization_id=parent.organization_id
WHERE child.organization_id IS NULL AND parent.organization_id IS NOT NULL;
UPDATE `document_comment` child JOIN `document` parent ON parent.id=child.document_id
SET child.organization_id=parent.organization_id
WHERE child.organization_id IS NULL AND parent.organization_id IS NOT NULL;
UPDATE `document_annotation` child JOIN `document` parent ON parent.id=child.document_id
SET child.organization_id=parent.organization_id
WHERE child.organization_id IS NULL AND parent.organization_id IS NOT NULL;
UPDATE `document_suggestion` child JOIN `document` parent ON parent.id=child.document_id
SET child.organization_id=parent.organization_id
WHERE child.organization_id IS NULL AND parent.organization_id IS NOT NULL;
UPDATE `document_secure_share` child JOIN `document` parent ON parent.id=child.document_id
SET child.organization_id=parent.organization_id
WHERE child.organization_id IS NULL AND parent.organization_id IS NOT NULL;
