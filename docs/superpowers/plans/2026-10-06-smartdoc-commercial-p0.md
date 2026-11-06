# SmartDoc Commercial P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement and verify the approved P0 security and reliability controls while preserving local Compose behavior and making no unsupported high-availability claims.

**Architecture:** Persist AI asynchronous jobs and their publish intent atomically in MySQL, dispatch Outbox events at least once, and make consumers idempotent. Reuse existing gateway Redis rate limiting, Actuator/Micrometer, Toolbox recovery, and backup operations; add only missing safety, resilience, metrics, alerting, and verification behavior.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Cloud 2023, MyBatis/JdbcTemplate, MySQL 8, Redis, RabbitMQ, Micrometer/Prometheus, PowerShell, Maven, Vue/Vite.

**Spec:** `docs/superpowers/specs/2026-10-06-smartdoc-commercial-p0-design.md`

## Global Constraints

- Preserve current public API paths and response envelopes.
- Keep local Docker Compose startup and local developer port mappings working.
- Add database changes only as new files under `init-db/migrations`; never modify applied migrations or delete user data.
- Do not persist or log raw Access Tokens, secrets, full document text, full user prompts, or model credentials.
- Tenant and document authorization failures remain fail-closed; no resilience fallback may grant access.
- At-least-once RabbitMQ delivery must be safe through idempotent state transitions.
- Production Compose exposes only 80/443; do not imply single-host Compose is multi-node HA.
- Tests use isolated fixtures/databases and must never point destructive operations at the live `doc_ai` database.

## Review Focus

- Database transaction rollback must leave neither an AI job nor its Outbox event visible.
- A publish timeout may mean RabbitMQ accepted the message; redelivery must not execute a completed job twice.
- Redis rate-limit failure must not silently disable authentication or make protected paths public.
- Authorization/document-service outage must deny access even while AI and rerank paths degrade.
- Backup/restore failure must not delete or overwrite production databases, volumes, secrets, or unrelated backup directories.

---

### Task 1: Define reliability targets and operational evidence

**Files:**
- Create: `docs/OPERATIONS_RELIABILITY.md`
- Create: `deploy/observability/prometheus.yml`
- Create: `deploy/observability/alert-rules.yml`
- Modify: `docker-compose.yml` to run Prometheus on the private Compose network with resource limits and persistent TSDB storage, without publishing a host port.
- Modify: `docker-compose.prod.yml` only if needed to preserve the same private-only boundary.
- Add bounded Micrometer metrics for AI job lifecycle/Outbox backlog and gateway limiter rejections.
- Test/verify: Prometheus configuration validation command and production Compose validation.

**Interfaces:**
- Prometheus scrapes the existing `/actuator/prometheus` endpoint on each application service over the internal network.
- Alert rules use bounded labels only: `service`, `route`, `status`, and `dependency`.

- [ ] **Step 1: Add operational acceptance cases** for SLI/SLO definitions, error-budget calculation, external-model latency separation, and the distinction between RPO/RTO objectives and measured results.
- [ ] **Step 2: Run the Prometheus config parser against the initial config** and confirm the intended target/alert failure before adding valid rules.
- [ ] **Step 3: Add private Prometheus deployment, scrape and alert configuration** for service health, request error/latency, queue or Outbox backlog, task outcomes, rate-limit rejections, and dependency failures; do not invent measured SLO values.
- [ ] **Step 4: Document target ownership and required measurements** for login, document read/write, upload, RAG search, and AI generation.
- [ ] **Step 5: Validate** with Compose config, `promtool check config deploy/observability/prometheus.yml`, and bounded-label tests; expected: valid config and only public 80/443 bindings.

### Task 2: Persist AI async job and Outbox atomically

**Files:**
- Create: `init-db/migrations/V20261006_01__ai_async_job_outbox.sql`
- Create: `ai-service/src/main/java/com/javaee/aiservice/async/AsyncAIJobRepository.java`
- Create: `ai-service/src/main/java/com/javaee/aiservice/async/AsyncAIOutboxRepository.java`
- Modify: `ai-service/src/main/java/com/javaee/aiservice/async/AsyncAIJobService.java`
- Modify: `ai-service/src/main/java/com/javaee/aiservice/async/AsyncAIJobVO.java`
- Test: `ai-service/src/test/java/com/javaee/aiservice/async/AsyncAIJobPersistenceTest.java`

**Interfaces:**
- `AsyncAIJobRepository.create(AsyncAIJobVO job, String organizationId, String encryptedPayload)` persists durable job input and ownership.
- `AsyncAIOutboxRepository.enqueue(String eventId, String jobId)` persists only the publish intent; the RabbitMQ message contains only the job ID and schema version.
- `AsyncAIJobService.submit(...)` keeps its current signature and response shape; it writes the job and Outbox row in one Spring transaction.
- Job reads and state transitions use MySQL as source of truth; Redis remains a reconstructible cache.
- Job payloads are AES-GCM encrypted before persistence using a purpose-derived key from `SMARTDOC_AI_CREDENTIAL_MASTER_KEY`; ciphertext carries an explicit format version. Submission fails closed when the key is missing/invalid or ciphertext authentication fails. Payloads expire after the configured retention window; key rotation requires pausing submission/dispatch and draining unexpired jobs before replacing the master key.

- [ ] **Step 1: Write tests** for submitted-job visibility, owner/tenant scoping, transaction rollback, AES-GCM round-trip/tamper detection, missing-key rejection, expiry, and ensuring outbox/Rabbit messages contain no payload text, access token, or credential.
- [ ] **Step 2: Run the tests and confirm expected failures** because durable repositories/tables are absent.
- [ ] **Step 3: Add the versioned schema** with ownership, status, encrypted payload, expiry, timestamps, claim/lease fields, and indexes; do not store bearer tokens or plaintext prompt/document bodies.
- [ ] **Step 4: Implement versioned AES-GCM payload protection, JDBC repositories, and transactional submit/read/state operations** with parameterized SQL, bounded payload validation, and DB-side expiry eligibility.
- [ ] **Step 5: Run isolated-database tests** and confirm rollback removes both job and Outbox row; run the migration against an ephemeral MySQL-compatible test database and confirm existing rows/tables are untouched.

### Task 3: Relay Outbox events and make AI consumers idempotent

**Files:**
- Create: `ai-service/src/main/java/com/javaee/aiservice/async/AsyncAIOutboxRelay.java`
- Modify: `ai-service/src/main/java/com/javaee/aiservice/async/AsyncAIJobListener.java`
- Modify: `ai-service/src/main/java/com/javaee/aiservice/config/AiRabbitMQConfig.java`
- Modify: `ai-service/src/main/resources/application.yml`
- Test: `ai-service/src/test/java/com/javaee/aiservice/async/AsyncAIOutboxRelayTest.java`
- Test: `ai-service/src/test/java/com/javaee/aiservice/async/AsyncAIJobListenerTest.java`

**Interfaces:**
- `AsyncAIOutboxRelay.publishPendingBatch()` claims at most the configured batch size, publishes with confirms, marks confirmed events sent, and schedules bounded retries after failure.
- `AsyncAIJobService.claimForProcessing(String jobId)` atomically permits one transition from queued to running and returns false for duplicate/terminal deliveries.
- Listener uses the persisted payload and ownership context associated with `jobId`; message fields cannot override stored user or tenant identity.

- [ ] **Step 1: Write failing tests** for publish failure/retry, confirm-before-mark-sent ordering, lease recovery, duplicate delivery, terminal-task redelivery, and spoofed message tenant/user fields.
- [ ] **Step 2: Run the focused tests and confirm failures** at the expected state transition or absent relay behavior.
- [ ] **Step 3: Implement bounded Outbox claiming, publisher confirms, retry backoff, and observable terminal failures.**
- [ ] Configure correlated publisher confirms and mandatory returns; do not mark an event sent until broker confirmation succeeds and the message was routable.
- [ ] **Step 4: Update the listener** to atomically claim by job ID, load and decrypt trusted context/payload from MySQL, persist results/errors, and refresh Redis cache only after durable state changes. Reject messages with unsupported versions, missing/expired jobs, or invalid payload authentication without invoking AI.
- [ ] **Step 5: Run relay and listener tests**; verify duplicate messages do not call AI service twice and no raw authorization value is present in the message.

### Task 4: Add timeout, circuit-breaker, and concurrency boundaries

**Files:**
- Modify: root `pom.xml` only if a compatible managed dependency is required.
- Modify: `ai-service/pom.xml`, `document-service/pom.xml` as required.
- Create: focused resilience configuration/classes under `common/src/main/java/com/javaee/common/resilience/` or the owning service package.
- Modify: `ai-service/src/main/java/com/javaee/aiservice/client/DocumentServiceClient.java`, AI provider/Rerank clients, and selected Feign configuration only where calls are confirmed in scope.
- Test: owning service tests under `ai-service/src/test/java/...` and `document-service/src/test/java/...`.

**Interfaces:**
- Internal authorization lookups fail closed on timeout/open circuit.
- Non-security AI/Rerank calls return a typed dependency-unavailable outcome; optional Rerank failure may use the already retrieved authorized candidates.
- Retry is limited to idempotent calls and a fixed small attempt budget; write calls are not automatically retried.
- Bulkhead limits are configurable and do not create unbounded queues.
- Circuit-breaker state and concurrency metrics use bounded dependency names; permission, tenant, document, and file-scan checks never use availability fallbacks.

- [ ] **Step 1: Add behavior tests** for connect/read timeout, circuit open/half-open behavior, bounded concurrent calls, no retry for writes, and fail-closed permission lookup.
- [ ] **Step 2: Run focused tests and confirm expected failures.**
- [ ] **Step 3: Add the minimum compatible resilience dependency/configuration** after checking compatibility with Spring Boot 3.2 and Spring Cloud 2023; keep defaults conservative and environment-overridable.
- [ ] **Step 4: Apply policies to concrete remote boundaries:** add connect/read/request timeouts to `DocumentServiceClient`, the Rerank HTTP request, AI model provider calls, and the Feign clients used in document/file services; add circuit-breaker/bulkhead only around AI provider/Rerank calls and permission-service calls with fail-closed behavior.
- [ ] **Step 5: Run focused tests** including allowed RAG fallback and denied access when the document authorization service is unavailable.

### Task 5: Harden rate-limit and sensitive-log boundaries

**Files:**
- Modify: `gateway-service/src/main/java/com/javaee/gateway/config/GatewayConfig.java` and existing key resolver/rate-limit configuration.
- Modify: AI Agent/RAG log call sites that currently log full task or query text.
- Test: `gateway-service/src/test/java/com/javaee/gateway/config/GatewayRateLimitBoundaryTest.java`
- Test: relevant AI log-safe behavior tests under `ai-service/src/test/java/...`.

**Interfaces:**
- Authenticated requests are keyed by verified user identity; unauthenticated public routes use a bounded trusted client-address key.
- Redis limiter errors cannot turn protected calls into unauthenticated calls; error behavior is explicit and monitored.
- Application logs retain trace/job IDs and safe metadata, not prompt/document bodies or credentials.
- Only the gateway's verified identity injection and explicitly trusted proxy chain may contribute to limiter keys; arbitrary client-supplied `X-User-Id`, `X-Forwarded-For`, or `X-Real-IP` values cannot select another user's bucket.

- [ ] **Step 1: Write tests** for authenticated identity keying, forged identity and forwarding headers, missing identity, trusted proxy address parsing, Redis limiter error behavior (protected route rejected, never passed through), and redaction of prompt/document/token values.
- [ ] **Step 2: Run tests and confirm the problematic branches fail.**
- [ ] **Step 3: Implement only the missing trusted-key resolution and fail-closed limiter error behavior; replace sensitive log arguments with safe metadata and verify rate limiting does not alter authentication decisions.**
- [ ] **Step 4: Run focused tests and existing gateway authentication/tenant tests.**

### Task 6: Secure backup and restore operations

**Files:**
- Modify: `deploy/scripts/backup.ps1`
- Modify: `deploy/scripts/restore-drill.ps1`
- Modify: `deploy/scripts/migrate.ps1`
- Create: `deploy/scripts/tests/BackupRestoreSafety.Tests.ps1`
- Modify: `P0_OPERATIONS.md` and `SECURITY_DEPLOYMENT.md` as needed.

**Interfaces:**
- MySQL credentials are supplied through a short-lived protected defaults file or another mechanism that does not place the password in process arguments; temporary secret files are removed in `finally`.
- Restore drill accepts only its fixed isolated database name and uniquely prefixed temporary volumes; it never accepts the production database as a target.
- Backup retention deletion is constrained to validated child directories under the explicit backup destination.

- [ ] **Step 1: Write PowerShell tests** using a mocked Docker command to assert credentials never appear in process arguments, fixed restore target allowlisting, temporary-resource cleanup on success/failure, checksum failure stops before restore, and bounded retention deletion under the explicitly resolved backup root.
- [ ] **Step 2: Run tests and confirm they fail against current script behavior.**
- [ ] **Step 3: Implement secure temporary MySQL defaults-file handling, strict restore target checks, guaranteed isolated-database cleanup, temporary-volume cleanup, backup integrity reporting, and validated retention deletion.**
- [ ] **Step 4: Run the isolated script tests; do not run a restore against the live `doc_ai` database.**

### Task 7: Complete regression and deployment verification

**Files:**
- Modify: `TESTING.md`, `P0_OPERATIONS.md`, and `docs/OPERATIONS_RELIABILITY.md` with exact commands and boundaries.
- Test: existing full backend/frontend/deployment suites plus all tests added above.

- [ ] **Step 1: Run the entire backend suite** with `mvn -B -s .mvn/test-settings.xml test` from repository root.
- [ ] **Step 2: Run frontend tests/build** with `npm test` and `npm run build` from `vue-test-app`.
- [ ] **Step 3: Validate deployment artifacts** with production Compose config validation, existing production port checker, Prometheus parser, and the isolated PowerShell safety tests.
- [ ] **Step 4: Review test output and Git diff** for secrets, destructive SQL, high-cardinality metrics, unbounded retries, and accidental local/user data.
- [ ] **Step 5: Report** passing evidence, any environment-blocked tests, and required manual multi-node failover drill; do not claim commercial HA until that drill passes.

## Plan Self-Review

- Spec coverage: targets, security/tenant boundaries, encrypted durable async recovery, resilience, metrics/alerts, backups/restores, and local Compose compatibility map to Tasks 1–7.
- Failure coverage: transaction rollback, payload tampering/key absence, ambiguous publisher outcome, duplicate delivery, Redis outage, forged limiter headers, authorization outage, external model timeout, checksum failure, and destructive restore targeting are covered by explicit tests.
- Boundary: cloud-provider selection, multi-node stateful clustering, and cross-region failover are excluded from this P0 plan as specified.
- Execution method: native implementation by the current agent, with TDD and full-suite verification after each dependent task group.
