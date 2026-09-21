# SmartDoc 第三阶段：工具箱任务可靠持久化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将工具箱任务从内存/Redis 主导改为 MySQL 真实状态源，使用 MinIO 保存结果，补齐幂等消费、失败重试、重启恢复和结果过期清理能力。

**Architecture:** MySQL 保存任务上下文、状态、重试次数和结果对象键；RabbitMQ 只负责调度消息；Redis 只缓存任务快照和进度，故障时不影响任务状态读取；MinIO 保存结果文件正文。消费者通过数据库条件更新抢占任务，重复消息不会重复执行，定时恢复器重新投递停滞任务。

**Tech Stack:** Java 17、Spring Boot 3.2、MyBatis-Plus、MySQL、Redis、RabbitMQ、MinIO、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-09-20-smartdoc-security-reliability-hardening-design.md`

## Global Constraints

- MySQL 是工具箱任务状态的唯一真实来源。
- RabbitMQ 负责调度，Redis 仅作为缓存和进度通道，MinIO 保存结果文件。
- 任务消费必须通过原子状态转换保证幂等，并支持重启恢复、失败重试和结果过期清理。
- 所有任务查询、重试、结果下载必须同时校验任务所属用户和企业组织范围。
- 不在 Redis 中写入结果文件二进制正文。

## Review Focus

- Redis 不可用时，任务状态、列表和结果下载仍应以 MySQL/MinIO 正常工作。
- 同一 RabbitMQ 消息重复投递时，工具处理逻辑只能执行一次。
- 消费进程在 PROCESSING 状态中断后，任务必须能够被恢复而不是永久卡住。
- 任务结果过期后，不能继续下载 MinIO 对象，历史任务状态仍可追踪。
- 组织或用户不匹配时，不能通过 jobId 读取、重试或下载他人的任务。

---

### Task 1: 任务表、实体与原子状态 Mapper

**Files:**
- Create: `init-db/migrations/V20260920_03__toolbox_job_persistence.sql`
- Create: `document-service/src/main/java/com/javaee/documentservice/entity/ToolboxJob.java`
- Create: `document-service/src/main/java/com/javaee/documentservice/mapper/ToolboxJobMapper.java`
- Create: `document-service/src/main/resources/mapper/ToolboxJobMapper.xml`
- Test: `document-service/src/test/java/com/javaee/documentservice/mapper/ToolboxJobMapperTest.java`

**Interfaces:**
- Produces `ToolboxJobMapper.selectOwned(jobId, userId, organizationId)`, `listOwned(userId, organizationId, limit)`, `claimPending(jobId, staleBefore)`, `markSuccess(...)`, `markFailure(...)`, `resetStaleProcessing(...)`, `markExpired(...)`.
- `ToolboxJob` exposes jobId, userId, organizationId, toolType, documentIdsJson, documentNamesJson, pages, status, progress, message, retryCount, resultObjectKey, fileName, contentType, createdAt, updatedAt, startedAt, finishedAt, expiresAt.

- [ ] **Step 1: Write the failing Mapper contract test**

```java
@Test
void mapperContractDeclaresAtomicStateOperations() {
    assertThat(ToolboxJobMapper.class.getDeclaredMethod("claimPending", String.class, Instant.class)).isNotNull();
    assertThat(ToolboxJobMapper.class.getDeclaredMethod("selectOwned", String.class, Long.class, String.class)).isNotNull();
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl document-service -am -Dtest=ToolboxJobMapperTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `ToolboxJobMapper` and its atomic methods do not exist.

- [ ] **Step 3: Add the schema, entity, mapper and XML SQL**

The migration must create `toolbox_job` with a primary key on `job_id`, tenant/user/status/result indexes, JSON text columns for document IDs/names, retry and expiry timestamps. Mapper updates must include the current status in `WHERE`, so a repeated message cannot claim a completed task.

- [ ] **Step 4: Run the focused contract test**

Run: `mvn -pl document-service -am -Dtest=ToolboxJobMapperTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add init-db/migrations/V20260920_03__toolbox_job_persistence.sql document-service/src/main/java/com/javaee/documentservice/entity/ToolboxJob.java document-service/src/main/java/com/javaee/documentservice/mapper/ToolboxJobMapper.java document-service/src/main/resources/mapper/ToolboxJobMapper.xml document-service/src/test/java/com/javaee/documentservice/mapper/ToolboxJobMapperTest.java
git commit -m "feat: persist toolbox job state in mysql"
```

### Task 2: MinIO 结果存储与服务状态转换

**Files:**
- Create: `document-service/src/main/java/com/javaee/documentservice/service/ToolboxResultStore.java`
- Modify: `document-service/src/main/java/com/javaee/documentservice/service/ToolboxJobService.java`
- Modify: `document-service/src/main/java/com/javaee/documentservice/controller/ToolboxJobController.java`
- Modify: `document-service/src/main/java/com/javaee/documentservice/mapper/ToolboxJobMapper.java`
- Modify: `document-service/src/main/resources/mapper/ToolboxJobMapper.xml`
- Test: `document-service/src/test/java/com/javaee/documentservice/service/ToolboxJobServiceTest.java`

**Interfaces:**
- Consumes `ToolboxJobMapper` and `ToolboxJob` from Task 1.
- Produces `ToolboxResultStore.put(job, bytes, contentType)`, `get(job)`, `delete(job)`.
- `ToolboxJobService` no longer owns in-memory job/result maps; `submit`, `snapshot`, `list`, `retry`, `output`, and `process` use the database and result store.

- [ ] **Step 1: Write failing service tests**

Cover: submit inserts PENDING; successful processing stores bytes through `ToolboxResultStore` and never writes a Redis byte array; duplicate processing skips after `claimPending` returns zero; retry performs a database state transition; output reads only from the result store after ownership/status validation.

- [ ] **Step 2: Run focused tests to verify RED**

Run: `mvn -pl document-service -am -Dtest=ToolboxJobServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the service still uses in-memory maps and Redis output values.

- [ ] **Step 3: Implement MinIO result store and database-backed service**

Use a tenant-scoped object key `toolbox-results/{organizationId}/{userId}/{jobId}/{fileName}`. Ensure the configured bucket exists before upload. Store only the object key and metadata in MySQL; use Redis for short-lived snapshots/progress only. Make state updates conditional on the expected current status.

- [ ] **Step 4: Run focused tests and the document-service suite**

Run: `mvn -pl document-service -am -Dtest=ToolboxJobServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` and then `mvn -pl document-service -am test`.

Expected: both commands PASS with no Redis output payload assertions remaining.

- [ ] **Step 5: Commit**

```bash
git add document-service/src/main/java/com/javaee/documentservice/service/ToolboxResultStore.java document-service/src/main/java/com/javaee/documentservice/service/ToolboxJobService.java document-service/src/main/java/com/javaee/documentservice/controller/ToolboxJobController.java document-service/src/test/java/com/javaee/documentservice/service/ToolboxJobServiceTest.java
git commit -m "feat: store toolbox results in minio"
```

### Task 3: 重启恢复、失败重试与过期清理

**Files:**
- Modify: `document-service/src/main/java/com/javaee/documentservice/service/ToolboxJobService.java`
- Modify: `document-service/src/main/java/com/javaee/documentservice/config/RabbitMQConfig.java`
- Modify: `document-service/src/main/java/com/javaee/documentservice/DocumentServiceApplication.java`
- Test: `document-service/src/test/java/com/javaee/documentservice/service/ToolboxJobRecoveryTest.java`

**Interfaces:**
- Consumes the conditional state methods and result store from Tasks 1–2.
- Produces scheduled recovery that republishes old PENDING jobs, resets stale PROCESSING jobs, and deletes expired result objects while marking jobs EXPIRED.

- [ ] **Step 1: Write failing recovery tests**

Test that a stale PROCESSING job is reset and republished, a PENDING job is republished after a broker interruption window, and an expired SUCCESS result is deleted from MinIO and marked EXPIRED.

- [ ] **Step 2: Run recovery tests to verify RED**

Run: `mvn -pl document-service -am -Dtest=ToolboxJobRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because no scheduled recovery or expiry cleanup exists.

- [ ] **Step 3: Implement scheduled recovery and bounded retry behavior**

Add a scheduled method with configurable delays and thresholds. Recovery must use conditional SQL updates before republishing, and expiry cleanup must delete the object before clearing its database key; failures remain retryable and are logged with job id and tenant context.

- [ ] **Step 4: Run recovery tests and document-service tests**

Run: `mvn -pl document-service -am -Dtest=ToolboxJobRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test` and `mvn -pl document-service -am test`.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add document-service/src/main/java/com/javaee/documentservice/service/ToolboxJobService.java document-service/src/main/java/com/javaee/documentservice/config/RabbitMQConfig.java document-service/src/test/java/com/javaee/documentservice/service/ToolboxJobRecoveryTest.java
git commit -m "feat: recover and expire toolbox jobs"
```

### Task 4: 第三阶段回归与部署说明

**Files:**
- Create: `document-service/src/test/java/com/javaee/documentservice/service/ToolboxJobReliabilityTest.java`
- Modify: `docs/superpowers/specs/2026-09-20-smartdoc-security-reliability-hardening-design.md`
- Modify: `README.md`

- [ ] **Step 1: Add regression coverage**

Exercise Redis outage fallback, tenant ownership rejection, duplicate delivery idempotency, result expiry and retry state transitions using the service boundary.

- [ ] **Step 2: Run the regression test to verify RED where behavior is missing**

Run: `mvn -pl document-service -am -Dtest=ToolboxJobReliabilityTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: any uncovered reliability contract fails before the final integration fixes.

- [ ] **Step 3: Complete documentation and migration instructions**

Document the V20260920_03 migration, required MinIO bucket configuration, recovery thresholds, and the rule that Redis contains no result bytes.

- [ ] **Step 4: Run the complete verification**

Run: `mvn test` and `git diff main...HEAD --check`.

Expected: Maven BUILD SUCCESS, all tests pass, and diff check is clean.

- [ ] **Step 5: Commit**

```bash
git add document-service/src/test/java/com/javaee/documentservice/service/ToolboxJobReliabilityTest.java docs/superpowers/specs/2026-09-20-smartdoc-security-reliability-hardening-design.md README.md
git commit -m "test: complete toolbox reliability regression"
```
