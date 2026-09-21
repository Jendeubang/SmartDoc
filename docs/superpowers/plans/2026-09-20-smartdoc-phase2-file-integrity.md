# 文件数据完整性实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让普通上传和分片合并都经过可补偿的临时对象、PENDING/READY 状态和内容去重流程，避免半成品文件、孤儿对象和失败覆盖旧文件。

**Architecture:** 在文件服务中增加 `FileObjectStore` 负责 MinIO/本地对象的临时写入、提升和删除，`ReliableFileUploader` 负责校验、扫描、元数据状态转换和重复文件替换。文件元数据服务提供按用户、企业、桶和 MD5 查询的严格接口；失败时清理临时对象并将已写入记录标记为 FAILED。

**Tech Stack:** Java 17、Spring Boot 3.2、MyBatis-Plus、MySQL、MinIO、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-09-20-smartdoc-security-reliability-hardening-design.md` 第 3.2 节。

## Global Constraints

- 数据库结构只允许通过新增迁移修改，不编辑已经执行过的迁移。
- 所有外部输入使用白名单、范围限制或结构化解析，不直接拼接 SQL。
- 失败流程必须有明确状态和补偿动作，禁止捕获异常后继续返回成功。
- 重复文件仅在新文件完整成功后替换旧文件，失败上传不得破坏旧文件。
- 文件列表和下载不能返回 PENDING、FAILED 元数据。
- 不在 Redis、MySQL、RabbitMQ 消息或日志中保存用户原始 Access Token。

## Review Focus

- MinIO 写入成功但数据库写入失败：临时对象必须被清理，且接口不能返回成功。
- 元数据已是 PENDING 但对象提升失败：记录必须进入 FAILED，旧 READY 文件保持可用。
- 新文件成功后删除旧对象失败：新元数据仍保持唯一可见，旧对象删除失败必须记录警告供后续清理。
- 同一内容跨用户或跨企业上传：只能在同一用户、企业和存储桶范围内去重，不能跨租户复用。
- 分片索引缺失、重复或越界：合并前必须拒绝，不得生成不完整文件。

### Task 1: 完善文件元数据状态与去重查询

**Files:**
- Create: `init-db/migrations/V20260920_02__file_upload_integrity.sql`
- Modify: `file-service/src/main/java/com/javaee/fileservice/mapper/FileMetadataMapper.java`
- Modify: `file-service/src/main/resources/mapper/FileMetadataMapper.xml`
- Modify: `file-service/src/main/java/com/javaee/fileservice/service/FileMetadataService.java`
- Modify: `file-service/src/main/java/com/javaee/fileservice/service/impl/FileMetadataServiceImpl.java`
- Test: `file-service/src/test/java/com/javaee/fileservice/service/impl/FileMetadataServiceImplTest.java`

**Interfaces:**
- Consumes: 当前用户 `createBy`、企业 `organizationId`、当前桶 `bucketName` 和内容 MD5。
- Produces: `FileMetadataService.findReadyByMd5(String md5, String createBy, String organizationId)`，只返回同用户、同企业、同桶且状态为 READY/ACTIVE 的最新记录。

- [ ] **Step 1: 编写失败测试**，覆盖同租户 MD5 查询参数、PENDING/FAILED 不可见，以及列表只返回 READY/ACTIVE。
- [ ] **Step 2: 运行 `mvn -pl file-service -am "-Dtest=FileMetadataServiceImplTest" test`，确认新接口/行为失败。**
- [ ] **Step 3: 新增迁移**：把新上传默认状态规范为 READY，并添加 `(bucket_name, organization_id, create_by, md5, status)` 查询索引；不修改旧迁移文件。
- [ ] **Step 4: 实现 Mapper、Service 严格查询和可见状态过滤；元数据保存/更新失败向上传流程抛出异常，不再静默成功。**
- [ ] **Step 5: 运行定向测试并提交：`git commit -m "fix: add file integrity metadata states"`。**

### Task 2: 实现可补偿对象上传器

**Files:**
- Create: `file-service/src/main/java/com/javaee/fileservice/storage/FileObjectStore.java`
- Create: `file-service/src/main/java/com/javaee/fileservice/service/ReliableFileUploader.java`
- Create: `file-service/src/test/java/com/javaee/fileservice/service/ReliableFileUploaderTest.java`
- Modify: `file-service/src/main/java/com/javaee/fileservice/security/FileUploadValidator.java`

**Interfaces:**
- Consumes: 文件名、Content-Type、字节数组、用户 ID、企业 ID。
- Produces: `ReliableFileUploader.UploadResult(fileId, fileName, contentType, bytes, md5)`；成功时元数据为 READY，失败时清理对象并将已创建元数据设为 FAILED。

- [ ] **Step 1: 编写失败测试**：成功路径验证 `put(temp) -> PENDING -> promote -> READY`；对象上传失败不写元数据；提升失败清理临时对象并标记 FAILED；同租户重复文件只保留新元数据；跨企业同 MD5 不删除旧文件。
- [ ] **Step 2: 运行 `mvn -pl file-service -am "-Dtest=ReliableFileUploaderTest" test`，确认类尚不存在或测试失败。**
- [ ] **Step 3: 实现 `FileObjectStore`**：MinIO 使用 `putObject`、`copyObject`、`removeObject`，本地存储使用受 `localPath` 限制的 `Files` 操作；临时对象键固定为 `.uploading/{userId}/{fileId}.{ext}`，正式对象键使用生成的 fileId。
- [ ] **Step 4: 实现 `ReliableFileUploader`**：先校验/扫描和计算 MD5，再写临时对象与 PENDING，提升后更新 READY，最后删除旧对象和旧元数据；异常路径按已完成步骤补偿，删除旧对象失败只记录警告且不回滚新 READY 文件。
- [ ] **Step 5: 扩展上传校验器提供字节数组校验，限制文件名、扩展名、大小和文件签名；不信任客户端提供的对象路径。**
- [ ] **Step 6: 运行定向测试并提交：`git commit -m "feat: add compensating file uploader"`。**

### Task 3: 接入普通上传、批量上传和分片合并

**Files:**
- Modify: `file-service/src/main/java/com/javaee/fileservice/service/impl/FileServiceImpl.java`
- Modify: `file-service/src/main/java/com/javaee/fileservice/service/FileService.java`
- Modify: `file-service/src/test/java/com/javaee/fileservice/service/ReliableFileUploaderTest.java`
- Modify: `file-service/src/test/java/com/javaee/fileservice/security/FileUploadValidatorTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ReliableFileUploader.upload(MultipartFile, userId, organizationId)` 和 `uploadBytes(fileName, contentType, bytes, userId, organizationId)`。
- Produces: 现有 `/api/files/upload`、`/upload-multiple`、`/upload-chunk`、`/merge-chunk` API 保持响应结构兼容；失败上传不创建 READY 文件，成功后继续原有文档解析流程。

- [ ] **Step 1: 为分片合并补失败测试**：totalChunks 必须为正、chunkIndex 必须在范围内、缺少任一分片必须拒绝且清理临时文件；成功合并走可靠上传器。
- [ ] **Step 2: 运行 file-service 定向测试确认失败。**
- [ ] **Step 3: 普通上传和批量上传改为调用可靠上传器；成功后再触发文档内容提取；删除原先“元数据保存失败仍继续返回成功”的逻辑。**
- [ ] **Step 4: 分片上传限制 fileId、chunkIndex、totalChunks 和临时文件数量，合并后将字节交给可靠上传器，并在成功/失败后清理 chunkMap 和临时文件。**
- [ ] **Step 5: 运行 `mvn -pl file-service -am test`，确认文件服务全量通过；提交 `git commit -m "fix: route uploads through integrity workflow"`。**

### Task 4: 第二阶段回归与审查

**Files:**
- Verify all files changed by Tasks 1-3.

- [ ] **Step 1: 运行 `mvn test`。**
- [ ] **Step 2: 运行 `npm test` 和 `npm run build`。**
- [ ] **Step 3: 静态扫描静默捕获、未限制分片和旧上传直写路径。**
- [ ] **Step 4: 运行 `git diff main...HEAD --check`、检查状态和迁移文件。**
- [ ] **Step 5: 对上传失败补偿、重复替换、租户隔离和分片合并执行一次阶段审查；发现高优先级问题先修复并重新回归。**
