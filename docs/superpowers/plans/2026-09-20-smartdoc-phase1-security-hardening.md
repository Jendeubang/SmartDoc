# SmartDoc 第一阶段安全加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成文件查询输入校验、工具箱去 JWT、网关默认拒绝、精确 CORS 和生产端口隔离，同时保持现有登录、文档、工具箱、公开分享与 WebSocket 功能可用。

**Architecture:** 把外部输入先归一化再进入数据层；把用户认证与服务间身份拆开，异步任务只携带经过校验的任务上下文；由 Spring Security WebFlux 建立网关认证上下文，Gateway GlobalFilter 只负责清理和注入可信头；开发与生产 Compose 通过显式覆盖文件隔离端口。

**Tech Stack:** Java 17、Spring Boot 3.2、Spring Security WebFlux/MVC、Spring Cloud Gateway、MyBatis-Plus、Redis、RabbitMQ、Docker Compose、JUnit 5、Mockito、AssertJ、PowerShell。

**Spec:** `docs/superpowers/specs/2026-09-20-smartdoc-security-reliability-hardening-design.md`

## Global Constraints

- 所有改动按五个阶段依次实施，每个阶段独立测试、评审和提交。
- 不在 Redis、MySQL、RabbitMQ 消息或日志中保存用户原始 Access Token。
- 所有外部输入使用白名单、范围限制或结构化解析，不直接拼接 SQL。
- 生产环境只向公网暴露 80/443；数据库、中间件和微服务端口只在容器网络内可见。
- 失败流程必须有明确状态和补偿动作，禁止捕获异常后继续返回成功。
- 第一阶段不修改前端已有接口路径和成功响应结构。
- `/ws/**` HTTP/SockJS 传输端点保持公开，AI 与文档服务继续在 STOMP CONNECT 和 SUBSCRIBE 阶段完成认证与授权。
- 每个任务严格执行红—绿测试循环，并在对应模块测试通过后独立提交。

## Review Focus

- `sortBy` 包含 SQL 元字符、空白和大小写变体时，只能回退安全默认列；Task 1 的策略与服务测试必须覆盖。
- 工具箱任务提交、重试、RabbitMQ 消息、Redis 值和文件下载请求均不得出现原始 Bearer Token；Task 2 必须逐层断言。
- Spring Security 与 GlobalFilter 的执行顺序不得导致公开接口被锁死或受保护接口绕过；Task 3 必须通过 WebTestClient 和过滤器测试覆盖。
- 恶意 Origin、空生产 Origin 和带凭证预检请求必须被拒绝，同时本地前端与 SockJS Origin 保持可用；Task 4 必须覆盖。
- Compose 合并规则不得重新发布基础文件中已移除的端口，生产配置只能出现 80/443；Task 5 的脚本必须检查最终合成配置而不是单个 YAML。

---

### Task 1: 文件排序白名单与分页边界

**Files:**
- Create: `file-service/src/main/java/com/javaee/fileservice/service/FileMetadataQueryPolicy.java`
- Modify: `file-service/src/main/java/com/javaee/fileservice/service/impl/FileMetadataServiceImpl.java:79-105`
- Create: `file-service/src/test/java/com/javaee/fileservice/service/FileMetadataQueryPolicyTest.java`
- Create: `file-service/src/test/java/com/javaee/fileservice/service/impl/FileMetadataServiceImplTest.java`

**Interfaces:**
- Consumes: Controller 传入的 `int page, int size, String sortBy, String direction`。
- Produces: `FileMetadataQueryPolicy.normalize(...) -> QuerySpec`，其中 `QuerySpec` 暴露 `page()`、`size()`、`column()`、`ascending()`。

- [ ] **Step 1: 为归一化策略编写失败测试**

```java
package com.javaee.fileservice.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FileMetadataQueryPolicyTest {
    @Test
    void acceptsOnlyKnownSortFieldsAndDirections() {
        assertThat(FileMetadataQueryPolicy.normalize(1, 20, "fileName", "ASC"))
                .isEqualTo(new FileMetadataQueryPolicy.QuerySpec(1, 20, "file_name", true));
        assertThat(FileMetadataQueryPolicy.normalize(1, 20, "id desc; drop table file_metadata", "asc"))
                .isEqualTo(new FileMetadataQueryPolicy.QuerySpec(1, 20, "create_time", true));
        assertThat(FileMetadataQueryPolicy.normalize(1, 20, "createTime", "sideways").ascending())
                .isFalse();
    }

    @Test
    void clampsPageAndSize() {
        var query = FileMetadataQueryPolicy.normalize(-5, 1_000_000, null, null);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(100);
        assertThat(query.column()).isEqualTo("create_time");
    }
}
```

- [ ] **Step 2: 运行策略测试并确认失败**

Run:

```powershell
mvn -pl file-service -am -Dtest=FileMetadataQueryPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是 `FileMetadataQueryPolicy` 尚不存在。

- [ ] **Step 3: 实现无状态白名单策略**

```java
public final class FileMetadataQueryPolicy {
    private static final Map<String, String> COLUMNS = Map.of(
            "createTime", "create_time",
            "updateTime", "update_time",
            "fileName", "file_name",
            "fileSize", "file_size");

    private FileMetadataQueryPolicy() {}

    public static QuerySpec normalize(int page, int size, String sortBy, String direction) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String column = COLUMNS.getOrDefault(sortBy, "create_time");
        boolean ascending = "asc".equalsIgnoreCase(direction);
        return new QuerySpec(safePage, safeSize, column, ascending);
    }

    public record QuerySpec(int page, int size, String column, boolean ascending) {}
}
```

- [ ] **Step 4: 让元数据服务只使用 QuerySpec 构造分页和排序**

```java
var query = FileMetadataQueryPolicy.normalize(page, size, sortBy, direction);
Page<FileMetadata> pageObj = new Page<>(query.page(), query.size());
QueryWrapper<FileMetadata> wrapper = new QueryWrapper<>();
applyCurrentBucketFilter(wrapper);
wrapper.orderBy(true, query.ascending(), query.column());
```

删除 `camelToSnake()`，避免任何未白名单字段进入 MyBatis-Plus SQL 片段。

- [ ] **Step 5: 编写服务集成边界测试**

```java
@Test
void maliciousSortNeverReachesSqlSegment() {
    when(storage.getStorageType()).thenReturn("local");
    when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

    service.getFileList(-1, 10_000, "id desc; drop table file_metadata", "asc");

    ArgumentCaptor<Wrapper<FileMetadata>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectPage(any(Page.class), wrapper.capture());
    assertThat(wrapper.getValue().getSqlSegment()).contains("create_time");
    assertThat(wrapper.getValue().getSqlSegment()).doesNotContain("drop table");
}
```

- [ ] **Step 6: 运行文件服务测试**

Run:

```powershell
mvn -pl file-service -am -Dtest=FileMetadataQueryPolicyTest,FileMetadataServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 两个测试类全部 PASS。

- [ ] **Step 7: 提交 Task 1**

```powershell
git add file-service/src/main/java/com/javaee/fileservice/service/FileMetadataQueryPolicy.java file-service/src/main/java/com/javaee/fileservice/service/impl/FileMetadataServiceImpl.java file-service/src/test/java/com/javaee/fileservice/service/FileMetadataQueryPolicyTest.java file-service/src/test/java/com/javaee/fileservice/service/impl/FileMetadataServiceImplTest.java
git commit -m "fix: whitelist file metadata sort fields"
```

---

### Task 2: 工具箱任务移除原始 Bearer Token

**Files:**
- Create: `document-service/src/main/java/com/javaee/documentservice/client/ToolboxFileClient.java`
- Modify: `document-service/src/main/java/com/javaee/documentservice/controller/ToolboxJobController.java:14-20`
- Modify: `document-service/src/main/java/com/javaee/documentservice/service/ToolboxJobService.java:55-235,361-379,457-470`
- Create: `document-service/src/test/java/com/javaee/documentservice/client/ToolboxFileClientTest.java`
- Create: `document-service/src/test/java/com/javaee/documentservice/service/ToolboxJobServiceTest.java`

**Interfaces:**
- Consumes: `InternalServiceTokenProvider.getRequiredToken()`、`DocumentVO.fileId/userId/organizationId`。
- Produces: `ToolboxJobService.submit(ToolboxJobRequest, Long)`、`retry(String, Long)`；`ToolboxFileClient.download(DocumentVO)`。

- [ ] **Step 1: 编写文件客户端请求头失败测试**

```java
@Test
void downloadUsesInternalIdentityWithoutAuthorizationHeader() {
    RestTemplate http = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    InternalServiceTokenProvider tokens = mock(InternalServiceTokenProvider.class);
    when(tokens.getRequiredToken()).thenReturn("service-secret");
    ToolboxFileClient client = new ToolboxFileClient(http, tokens, "http://file-service:8082");

    DocumentVO document = new DocumentVO();
    document.setFileId("file-1");
    document.setUserId(7L);
    document.setOrganizationId("org-1");

    server.expect(requestTo("http://file-service:8082/api/files/download/file-1"))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andExpect(header("X-Internal-Service-Token", "service-secret"))
            .andExpect(header("X-User-Id", "7"))
            .andExpect(header("X-Organization-Id", "org-1"))
            .andRespond(withSuccess("source", MediaType.APPLICATION_OCTET_STREAM));

    assertThat(client.download(document)).isEqualTo("source".getBytes(StandardCharsets.UTF_8));
    server.verify();
}
```

- [ ] **Step 2: 运行客户端测试并确认失败**

Run:

```powershell
mvn -pl document-service -am -Dtest=ToolboxFileClientTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是 `ToolboxFileClient` 尚不存在。

- [ ] **Step 3: 实现专用内部文件客户端**

```java
@Component
public class ToolboxFileClient {
    private final RestTemplate http;
    private final InternalServiceTokenProvider tokens;
    private final String fileUrl;

    public ToolboxFileClient(InternalServiceTokenProvider tokens,
            @Value("${file.service.url:http://localhost:8082}") String fileUrl) {
        this(new RestTemplate(), tokens, fileUrl);
    }

    ToolboxFileClient(RestTemplate http, InternalServiceTokenProvider tokens, String fileUrl) {
        this.http = http;
        this.tokens = tokens;
        this.fileUrl = fileUrl;
    }

    public byte[] download(DocumentVO document) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Service-Token", tokens.getRequiredToken());
        headers.set("X-User-Id", String.valueOf(document.getUserId()));
        if (document.getOrganizationId() != null && !document.getOrganizationId().isBlank()) {
            headers.set("X-Organization-Id", document.getOrganizationId());
        }
        var response = http.exchange(fileUrl + "/api/files/download/" + document.getFileId(),
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        if (response.getBody() == null) throw new BusinessException("File service returned an empty source file");
        return response.getBody();
    }
}
```

保留现有空 `fileId` 校验，并对路径变量使用 `UriComponentsBuilder` 编码，测试中的固定字符串只展示主要接口。

- [ ] **Step 4: 编写任务内容不持久化 Token 的失败测试**

```java
@Test
void submitPersistsOnlyTaskContextAndNeverAuthorization() {
    ToolboxJobRequest request = new ToolboxJobRequest();
    request.setToolType("OCR");
    request.setDocumentIds(List.of("doc-1"));
    when(documents.getById("doc-1", 7L)).thenReturn(document("doc-1", 7L));

    jobs.submit(request, 7L);

    ArgumentCaptor<Object> redisValue = ArgumentCaptor.forClass(Object.class);
    verify(values, atLeastOnce()).set(anyString(), redisValue.capture(), anyLong(), any(TimeUnit.class));
    assertThat(redisValue.getAllValues()).allSatisfy(value ->
            assertThat(String.valueOf(value)).doesNotContain("Bearer "));
    ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
    verify(rabbit).convertAndSend(eq("file.exchange"), eq("document.toolbox"), message.capture());
    assertThat(String.valueOf(message.getValue())).doesNotContain("Authorization").doesNotContain("Bearer ");
}
```

- [ ] **Step 5: 删除 Task Service 的令牌状态并调整公开方法签名**

删除以下成员和方法：

```text
REDIS_TOKEN_PREFIX
jobTokens
requestToken
persistToken(...)
loadToken(...)
```

签名改为：

```java
public Map<String, Object> submit(ToolboxJobRequest request, Long userId)
public Map<String, Object> retry(String jobId, Long userId)
```

`process()` 不再加载、设置或清理用户 Token，所有 `download(document)` 改为 `fileClient.download(document)`。

- [ ] **Step 6: 修改 Controller，不再要求 Authorization 参数**

```java
@PostMapping
public Result<Map<String, Object>> submit(@RequestBody ToolboxJobRequest request) {
    return Result.success(jobs.submit(request, users.getRequiredUserId()));
}

@PostMapping("/{jobId}/retry")
public Result<Map<String, Object>> retry(@PathVariable String jobId) {
    return Result.success(jobs.retry(jobId, users.getRequiredUserId()));
}
```

- [ ] **Step 7: 运行工具箱测试和文档服务全量测试**

Run:

```powershell
mvn -pl document-service -am -Dtest=ToolboxFileClientTest,ToolboxJobServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl document-service -am test
```

Expected: PASS；测试捕获的 Redis 值和 RabbitMQ 消息不包含 `Bearer ` 或 `Authorization`。

- [ ] **Step 8: 静态扫描令牌持久化残留**

Run:

```powershell
rg -n "REDIS_TOKEN_PREFIX|jobTokens|requestToken|persistToken|loadToken" document-service/src
```

Expected: 无匹配结果。

- [ ] **Step 9: 提交 Task 2**

```powershell
git add document-service/src/main/java/com/javaee/documentservice/client/ToolboxFileClient.java document-service/src/main/java/com/javaee/documentservice/controller/ToolboxJobController.java document-service/src/main/java/com/javaee/documentservice/service/ToolboxJobService.java document-service/src/test/java/com/javaee/documentservice/client/ToolboxFileClientTest.java document-service/src/test/java/com/javaee/documentservice/service/ToolboxJobServiceTest.java
git commit -m "security: remove bearer tokens from toolbox jobs"
```

---

### Task 3: 网关认证切换为 Spring Security 默认拒绝

**Files:**
- Create: `gateway-service/src/main/java/com/javaee/gateway/security/GatewayPrincipal.java`
- Create: `gateway-service/src/main/java/com/javaee/gateway/security/GatewayAuthenticationManager.java`
- Create: `gateway-service/src/main/java/com/javaee/gateway/security/BearerTokenServerAuthenticationConverter.java`
- Modify: `gateway-service/src/main/java/com/javaee/gateway/config/SecurityConfig.java:14-29`
- Modify: `gateway-service/src/main/java/com/javaee/gateway/filter/AuthGlobalFilter.java:41-207`
- Create: `gateway-service/src/test/java/com/javaee/gateway/security/GatewayAuthenticationManagerTest.java`
- Create: `gateway-service/src/test/java/com/javaee/gateway/config/SecurityConfigTest.java`
- Modify: `gateway-service/src/test/java/com/javaee/gateway/filter/AuthGlobalFilterTest.java`

**Interfaces:**
- Consumes: `JwtUtils.validateAccessToken/getUserId/getUsername/getRole/getSessionId/getSessionVersion` 与 Redis 键 `auth:session:{sid}`、`auth:user-version:{userId}`。
- Produces: `GatewayPrincipal(Long userId, String username, String role)`，供 `AuthGlobalFilter` 注入可信下游请求头。

- [ ] **Step 1: 编写认证管理器失败测试**

```java
@Test
void acceptsOnlyActiveAccessSession() {
    String token = JwtUtils.generateToken(7L, "member", "USER", "sid-1", 3);
    when(values.get("auth:session:sid-1")).thenReturn(Mono.just("7"));
    when(values.get("auth:user-version:7")).thenReturn(Mono.just("3"));

    Authentication result = manager.authenticate(
            new UsernamePasswordAuthenticationToken("Bearer", token)).block();

    assertThat(result).isNotNull();
    assertThat(result.getPrincipal()).isEqualTo(new GatewayPrincipal(7L, "member", "USER"));
}

@Test
void rejectsRefreshTokenAndRevokedSession() {
    String refresh = JwtUtils.generateRefreshToken(7L, "sid-1", 3);
    StepVerifier.create(manager.authenticate(new UsernamePasswordAuthenticationToken("Bearer", refresh)))
            .expectError(BadCredentialsException.class).verify();
}
```

- [ ] **Step 2: 运行认证管理器测试并确认失败**

Run:

```powershell
mvn -pl gateway-service -am -Dtest=GatewayAuthenticationManagerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是三个安全类尚不存在。

- [ ] **Step 3: 实现 Bearer 转换器、Principal 和响应式认证管理器**

核心认证返回值：

```java
GatewayPrincipal principal = new GatewayPrincipal(userId, username, normalizedRole);
return Mono.just(new UsernamePasswordAuthenticationToken(
        principal,
        token,
        List.of(new SimpleGrantedAuthority("ROLE_" + normalizedRole))));
```

Redis 会话归属或版本不匹配时返回：

```java
return Mono.error(new BadCredentialsException("Session has been revoked"));
```

转换器只接受严格的 `Authorization: Bearer <non-empty-token>`；缺失 Header 返回 `Mono.empty()`，错误前缀返回 `BadCredentialsException`。

- [ ] **Step 4: 编写默认拒绝 SecurityWebFilterChain 测试**

```java
@Test
void protectedRouteRequiresAuthenticationButPublicAndSockJsRoutesRemainReachable() {
    webTestClient.get().uri("/api/documents").exchange().expectStatus().isUnauthorized();
    webTestClient.post().uri("/api/users/login").exchange().expectStatus().isNotEqualTo(HttpStatus.UNAUTHORIZED);
    webTestClient.get().uri("/api/public/shares/token/content").exchange()
            .expectStatus().isNotEqualTo(HttpStatus.UNAUTHORIZED);
    webTestClient.get().uri("/ws/agent/info").exchange()
            .expectStatus().isNotEqualTo(HttpStatus.UNAUTHORIZED);
}
```

- [ ] **Step 5: 配置 Spring Security 认证过滤器和白名单**

```java
.authorizeExchange(exchanges -> exchanges
    .pathMatchers("/api/users/register", "/api/users/login", "/api/users/refresh",
        "/api/users/password/forgot", "/api/users/password/reset",
        "/api/public/shares/**", "/actuator/health", "/ws/**").permitAll()
    .anyExchange().authenticated())
```

使用 `AuthenticationWebFilter`、`SecurityWebFiltersOrder.AUTHENTICATION`、`NoOpServerSecurityContextRepository` 和返回 401 的认证入口点。关闭 CSRF、HTTP Basic、Form Login 和有状态 SecurityContext。

- [ ] **Step 6: 将 AuthGlobalFilter 缩减为可信头处理**

过滤器必须先删除：

```text
X-User-Id
X-Username
X-Role
X-Organization-Id
X-Internal-Service-Token
```

然后从 `exchange.getPrincipal()` 获取 `GatewayPrincipal`。认证存在时注入用户、角色和内部令牌；公开请求没有 Principal 时只转发已经清理的请求。删除 `WHITE_LIST`、JWT 解析、Redis 会话校验和 `unauthorized()`，避免双重认证规则。

- [ ] **Step 7: 更新 GlobalFilter 测试**

测试用 `ReactiveSecurityContextHolder.withAuthentication(...)` 或 `exchange.getPrincipal()` 可见的测试 Principal 驱动过滤器，继续断言：

```java
assertThat(headers.getFirst("X-User-Id")).isEqualTo("7");
assertThat(headers.getFirst("X-Username")).isEqualTo("member");
assertThat(headers.getFirst("X-Role")).isEqualTo("USER");
assertThat(headers.getFirst("X-Internal-Service-Token")).isEqualTo("internal-service-secret");
```

公开路由必须继续清除伪造头。

- [ ] **Step 8: 运行网关安全测试和网关全量测试**

Run:

```powershell
mvn -pl gateway-service -am -Dtest=GatewayAuthenticationManagerTest,SecurityConfigTest,AuthGlobalFilterTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl gateway-service -am test
```

Expected: PASS；受保护路由无 Token 为 401，公开路由不被认证链阻断，伪造头不会到达下游。

- [ ] **Step 9: 提交 Task 3**

```powershell
git add gateway-service/src/main gateway-service/src/test
git commit -m "security: enforce gateway authentication by default"
```

---

### Task 4: 统一精确 CORS 白名单

**Files:**
- Create: `common/src/main/java/com/javaee/common/config/CorsOriginPolicy.java`
- Modify: `common/src/main/java/com/javaee/common/config/WebMvcConfig.java`
- Modify: `gateway-service/src/main/java/com/javaee/gateway/config/GlobalCorsConfig.java`
- Create: `common/src/test/java/com/javaee/common/config/CorsOriginPolicyTest.java`
- Create: `gateway-service/src/test/java/com/javaee/gateway/config/GlobalCorsConfigTest.java`
- Modify: `.env.example`

**Interfaces:**
- Consumes: `security.cors.allowed-origins` / `SECURITY_CORS_ALLOWED_ORIGINS`，逗号分隔精确 Origin。
- Produces: `CorsOriginPolicy.parse(String configured, boolean production) -> List<String>`。

- [ ] **Step 1: 编写 Origin 解析策略失败测试**

```java
@Test
void normalizesUniqueExactOriginsAndRejectsWildcard() {
    assertThat(CorsOriginPolicy.parse(" http://localhost:5173,https://docs.example.com,http://localhost:5173 ", false))
            .containsExactly("http://localhost:5173", "https://docs.example.com");
    assertThatThrownBy(() -> CorsOriginPolicy.parse("*", false))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void productionRequiresExplicitOrigin() {
    assertThatThrownBy(() -> CorsOriginPolicy.parse(" ", true))
            .isInstanceOf(IllegalStateException.class);
    assertThat(CorsOriginPolicy.parse(" ", false)).containsExactly("http://localhost:5173");
}
```

- [ ] **Step 2: 运行策略测试并确认失败**

Run:

```powershell
mvn -pl common -Dtest=CorsOriginPolicyTest test
```

Expected: FAIL，原因是 `CorsOriginPolicy` 尚不存在。

- [ ] **Step 3: 实现共享 Origin 解析策略**

策略只接受 `http://` 或 `https://` 开头且没有路径、查询和 Fragment 的 Origin；去重后保持输入顺序；禁止 `*`。非生产空配置回退 `http://localhost:5173`，生产空配置抛出启动异常。

- [ ] **Step 4: 修改 MVC 与 Gateway CORS 配置**

两端统一使用：

```java
configuration.setAllowedOrigins(origins);
configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
configuration.setAllowedHeaders(List.of(
        "Authorization", "Content-Type", "Accept", "X-Organization-Id", "X-Requested-With"));
configuration.setExposedHeaders(List.of("Content-Disposition"));
configuration.setAllowCredentials(false);
configuration.setMaxAge(3600L);
```

MVC 使用 `CorsRegistry` 的等价配置；禁止 `allowedOriginPatterns("*")`、`allowedHeaders("*")` 和 `allowCredentials(true)`。

- [ ] **Step 5: 编写 Gateway 预检请求测试**

```java
@Test
void allowsConfiguredOriginAndRejectsUnknownOrigin() {
    client.options().uri("/api/documents")
            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");

    client.options().uri("/api/documents")
            .header(HttpHeaders.ORIGIN, "https://evil.example.com")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
}
```

- [ ] **Step 6: 更新环境变量示例并扫描通配符**

`.env.example` 保留：

```dotenv
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

Run:

```powershell
rg -n "allowedOriginPatterns\(\"\*\"\)|allowedHeaders\(\"\*\"\)|setAllowCredentials\(true\)|allowCredentials\(true\)" common/src gateway-service/src
```

Expected: 无匹配结果。

- [ ] **Step 7: 运行 CORS 测试**

Run:

```powershell
mvn -pl common,gateway-service -am -Dtest=CorsOriginPolicyTest,GlobalCorsConfigTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 8: 提交 Task 4**

```powershell
git add common/src/main/java/com/javaee/common/config common/src/test/java/com/javaee/common/config gateway-service/src/main/java/com/javaee/gateway/config/GlobalCorsConfig.java gateway-service/src/test/java/com/javaee/gateway/config/GlobalCorsConfigTest.java .env.example
git commit -m "security: restrict cors origins"
```

---

### Task 5: 开发与生产 Compose 端口隔离

**Files:**
- Create: `docker-compose.dev.yml`
- Modify: `docker-compose.yml:316-356`
- Modify: `docker-compose.prod.yml:19-45`
- Modify: `docker-compose-infra.yml:14-71`
- Modify: `docker-compose-services.yml:23-123`
- Create: `deploy/verify-production-ports.ps1`
- Modify: `README.md:118-147,182-204`

**Interfaces:**
- Consumes: `docker compose -f docker-compose.yml -f docker-compose.prod.yml config --format json`。
- Produces: 生产合成配置只发布 80/443；开发覆盖文件仅绑定 `127.0.0.1`。

- [ ] **Step 1: 先编写生产端口检查脚本**

```powershell
$repoRoot = Split-Path -Parent $PSScriptRoot
$json = & docker compose `
  -f (Join-Path $repoRoot 'docker-compose.yml') `
  -f (Join-Path $repoRoot 'docker-compose.prod.yml') `
  config --format json
if ($LASTEXITCODE -ne 0) { throw 'docker compose config failed' }
$config = $json | ConvertFrom-Json
$violations = @()
foreach ($service in $config.services.PSObject.Properties) {
  foreach ($port in @($service.Value.ports)) {
    if ($null -eq $port) { continue }
    $published = [int]$port.published
    if ($published -notin @(80, 443)) {
      $violations += "$($service.Name):$published"
    }
  }
}
if ($violations.Count -gt 0) {
  throw "Unexpected production ports: $($violations -join ', ')"
}
Write-Host 'Production port boundary verified: only 80/443 are published.'
```

- [ ] **Step 2: 运行脚本记录当前基线**

Run:

```powershell
./deploy/verify-production-ports.ps1
```

Expected: 当前生产覆盖若已正确重置 8080 则 PASS；即使基线 PASS，后续步骤仍用于消除安全配置歧义并防回归。

- [ ] **Step 3: 把基础 Compose 改为纯内部网络**

`docker-compose.yml` 中 `gateway-service` 删除：

```yaml
ports:
  - "127.0.0.1:8080:8080"
```

改为：

```yaml
expose:
  - "8080"
```

其余基础服务继续只使用 `expose`。

- [ ] **Step 4: 新增仅本机可见的开发覆盖文件**

```yaml
services:
  mysql:
    ports: ["127.0.0.1:3306:3306"]
  redis:
    ports: ["127.0.0.1:6379:6379"]
  rabbitmq:
    ports: ["127.0.0.1:5672:5672", "127.0.0.1:15672:15672"]
  minio:
    ports: ["127.0.0.1:9000:9000", "127.0.0.1:9001:9001"]
  gateway-service:
    ports: ["127.0.0.1:8080:8080"]
```

- [ ] **Step 5: 清理生产覆盖和旧配置的公开内部端口**

`docker-compose.prod.yml` 保留 reverse-proxy 的 80/443，删除不再需要的 `ports: !reset []`。`docker-compose-infra.yml` 的调试端口全部添加 `127.0.0.1:` 前缀。`docker-compose-services.yml` 的用户、文件、AI、文档端口改为 `expose`；网关端口至少绑定到 `127.0.0.1:8080:8080`，并在文件头注明该文件仅供本机/旧环境调试，不作为生产入口。

- [ ] **Step 6: 更新 README 启动命令**

开发环境：

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

生产环境：

```powershell
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
./deploy/verify-production-ports.ps1
```

README 明确说明 `docker-compose-infra.yml` 和 `docker-compose-services.yml` 不是公网生产入口。

- [ ] **Step 7: 验证开发与生产合成配置**

Run:

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.prod.yml config --quiet
./deploy/verify-production-ports.ps1
```

Expected: 两个配置校验成功；生产检查只发现 80/443。

- [ ] **Step 8: 提交 Task 5**

```powershell
git add docker-compose.yml docker-compose.dev.yml docker-compose.prod.yml docker-compose-infra.yml docker-compose-services.yml deploy/verify-production-ports.ps1 README.md
git commit -m "ops: isolate production service ports"
```

---

### Task 6: 第一阶段完整回归与独立审查

**Files:**
- Modify only if verification reveals a stage-one regression; changes must be committed with the task that introduced the regression.
- Verify: all files changed by Tasks 1-5.

**Interfaces:**
- Consumes: Tasks 1-5 的全部实现和测试。
- Produces: 第一阶段可验收分支，后续第二阶段以此提交为基线。

- [ ] **Step 1: 运行后端全量测试**

Run:

```powershell
mvn test
```

Expected: `BUILD SUCCESS`，Failures 0，Errors 0；允许保留当前显式跳过的外部模型测试。

- [ ] **Step 2: 运行前端测试和生产构建**

Run:

```powershell
Set-Location vue-test-app
npm test
npm run build
Set-Location ..
```

Expected: 8 项现有 Node 测试全部通过，Vite 构建成功；大 chunk 警告不作为第一阶段阻断项。

- [ ] **Step 3: 运行静态安全扫描**

Run:

```powershell
rg -n "REDIS_TOKEN_PREFIX|jobTokens|persistToken|loadToken|allowedOriginPatterns\(\"\*\"\)|allowCredentials\(true\)|anyExchange\(\)\.permitAll" common/src gateway-service/src document-service/src
```

Expected: 无业务代码匹配；测试名称或设计文档中的说明不计入扫描范围。

- [ ] **Step 4: 验证生产端口**

Run:

```powershell
./deploy/verify-production-ports.ps1
```

Expected: `Production port boundary verified: only 80/443 are published.`

- [ ] **Step 5: 检查改动和密钥泄漏**

Run:

```powershell
git diff main...HEAD --check
git status --short
git diff main...HEAD -- .env .env.example
```

Expected: 无 diff 格式错误；工作区干净；`.env` 未被跟踪，`.env.example` 只包含占位符。

- [ ] **Step 6: 使用 Superpowers 请求一次阶段代码审查**

审查重点依次为：认证绕过、Token 泄漏、SQL 片段、CORS 回归、Compose 端口。阻断级和高优先级问题修复并重新执行 Steps 1-5 后，第一阶段才可标记完成。

- [ ] **Step 7: 记录第一阶段完成提交**

若审查没有产生代码修改，不创建空提交；以 Task 5 的最后一个业务提交作为阶段完成点。若审查产生修复，使用：

```powershell
git status --short
git add -u
git commit -m "fix: address phase one security review"
```
