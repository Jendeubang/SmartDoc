# SmartDoc 第四、第五阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将文件上传与分片合并改为受限流式处理，并让 RAG 在向量召回、BM25 和 Rerank 之前使用服务端授权文档集合进行候选过滤。

**Architecture:** 文件对象存储增加 `InputStream + size` 写入能力；普通上传只用流计算摘要、扫描和写入临时对象，分片合并使用临时文件流式拼接，不把完整文件或全部分片装入 JVM。批量上传在服务端执行文件数、总容量和单批并发边界校验。RAG 将授权文档 ID 和已验证租户上下文转换为候选过滤条件，HNSW、Redis 向量检索和本地 BM25 共用该条件，只有授权候选进入融合、重排和上下文构建。

**Tech Stack:** Java 17、Spring Boot 3.2、MinIO、MySQL、Redis、RabbitMQ、Spring AI、HNSW、BM25、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-09-20-smartdoc-security-reliability-hardening-design.md`

## Global Constraints

- 普通上传改用输入流写入 MinIO，分片合并不得把完整文件和所有分片加载进 JVM 堆。
- 批量上传必须限制单文件大小、文件数、总容量和并发上限。
- HNSW 索引按 `organizationId + knowledgeBaseId` 隔离；向量维度不一致时拒绝写入并回退 Redis，现有索引不破坏。
- 检索前获取服务端授权文档集合，授权过滤先于 BM25 融合、Rerank 和 Prompt 构建。
- 客户端提交的 `X-Organization-Id` 不能作为授权依据，只使用 TenantContext 和 document-service 返回的授权集合。

## Review Focus

- 大文件普通上传不能调用 `MultipartFile.getBytes()`；存储异常必须清理临时对象和失败元数据。
- 分片重复、缺失、超限和合并失败不能泄漏临时文件，合并过程不应出现按总文件大小增长的 JVM byte[]。
- 100 份批量上传必须在边界内稳定执行，超过文件数或总容量应在写入前拒绝。
- 无权限文档不能进入 BM25、Rerank 或上下文构建；跨组织同知识库不能命中。
- 空授权集合、缺失租户上下文、Redis/HNSW 不可用时必须安全降级，不得扩大检索范围。

---

### Task 1: 流式对象写入与普通上传

**Files:**
- Modify: `file-service/src/main/java/com/javaee/fileservice/storage/FileObjectStore.java`
- Modify: `file-service/src/main/java/com/javaee/fileservice/security/MalwareScanner.java`
- Modify: `file-service/src/main/java/com/javaee/fileservice/service/ReliableFileUploader.java`
- Test: `file-service/src/test/java/com/javaee/fileservice/service/ReliableFileUploaderTest.java`

**Interfaces:**
- Produce `FileObjectStore.put(String, InputStream, long, String)`.
- Produce `MalwareScanner.scan(InputStream)` while preserving the byte-array overload.
- `ReliableFileUploader.upload(MultipartFile, ...)` returns metadata without retaining the entire multipart body; byte-array upload remains for compatibility with chunk tests.

- [x] Write a failing test proving multipart upload uses the stream overload and returns no retained body.
- [x] Run the focused test and observe failure because the stream contract is absent.
- [x] Add stream-based MinIO/local writes, stream MD5 calculation, stream ClamAV scanning, and a repeatable upload source for the compensation workflow.
- [x] Run focused uploader and file-service regression tests.

### Task 2: 批量边界与分片流式合并

**Files:**
- Modify: `file-service/src/main/java/com/javaee/fileservice/config/FileStorageConfig.java`
- Modify: `file-service/src/main/java/com/javaee/fileservice/service/impl/FileServiceImpl.java`
- Test: `file-service/src/test/java/com/javaee/fileservice/service/impl/FileServiceImplChunkTest.java`
- Test: `file-service/src/test/java/com/javaee/fileservice/service/impl/FileServiceImplBatchTest.java`

**Interfaces:**
- Add configurable `maxBatchFiles`, `maxBatchTotalSizeMb`, and `maxBatchConcurrency` with safe defaults.
- `uploadMultiple` validates the complete batch before the first write and processes within the configured bound.
- `mergeChunk` streams ordered chunk files into a temporary merged file, then uploads through a stream-aware uploader and always cleans the session.

- [x] Write failing tests for batch count/total-size rejection and chunk merge without `Files.readAllBytes`.
- [x] Run focused tests and observe failure.
- [x] Implement configuration binding, preflight batch validation, sequential bounded processing, and temporary-file streaming merge.
- [x] Run file-service focused and module tests.
- [x] Commit `feat: bound batch uploads and stream chunk merges`.

### Task 3: 权限感知候选生成

**Files:**
- Modify: `ai-service/src/main/java/com/javaee/aiservice/rag/PermissionAwareRagService.java`
- Modify: `ai-service/src/main/java/com/javaee/aiservice/rag/KnowledgeBase.java`
- Modify: `ai-service/src/main/java/com/javaee/aiservice/rag/VectorStore.java`
- Modify: `ai-service/src/main/java/com/javaee/aiservice/rag/HnswIndexManager.java`
- Modify: `ai-service/src/main/java/com/javaee/aiservice/security/TenantContextFilter.java` only if a server-side context accessor is needed
- Test: `ai-service/src/test/java/com/javaee/aiservice/rag/PermissionAwareRagServiceTest.java`
- Test: `ai-service/src/test/java/com/javaee/aiservice/rag/KnowledgeBasePermissionFilterTest.java`

**Interfaces:**
- Permission service supplies an immutable authorized document ID set and validated organization ID.
- A filter value may represent one exact metadata value or a set of allowed values; HNSW, Redis TAG expression and BM25 must interpret it consistently.
- `KnowledgeBase` applies authorization filters before vector/BM25 merge and before Reranker invocation.

- [x] Write failing tests proving unauthorized candidates never reach the reranker and same-knowledge-base documents from another organization are excluded.
- [x] Run focused tests and observe failure because current filtering happens after candidate generation.
- [x] Implement authorized-set filters, tenant filter injection, HNSW set matching and Redis-safe OR expressions; empty authorization returns no results.
- [x] Run focused RAG tests and the ai-service module tests.

### Task 4: 两阶段回归与文档

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-20-smartdoc-security-reliability-hardening-design.md`
- Test: file and AI regression suites.

- [x] Document upload limits, stream behavior, chunk cleanup, HNSW scope and pre-rerank authorization behavior.
- [x] Run root `mvn test` and `git diff --check`.
- [x] Review the complete Phase 4–5 diff for security and resource-boundary regressions.
