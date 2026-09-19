package com.javaee.gateway.filter;

/**
 * 【简历：网关 JWT 鉴权过滤器】
 * Spring Cloud Gateway 全局过滤器，对请求进行 JWT Token 校验，
 * 鉴权通过后将用户信息注入请求头转发给下游服务。
 */

import com.javaee.common.utils.JwtUtils;
import com.javaee.common.config.security.InternalServiceTokenProvider;
import com.javaee.gateway.config.RabbitMQConfig;
import com.javaee.gateway.util.RabbitMQUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT鉴权全局过滤器
 */
// 【简历第6条 · 网关鉴权过滤器】核心流程：清空客户端伪造身份头 → 白名单放行 →
// 校验 JWT → 校验 Redis 会话/版本号（支持强制下线）→ 注入 X-User-Id/X-Username/X-Role 转发下游
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${security.internal-service-token:}")
    private String internalServiceToken;

    @Autowired
    private RabbitMQUtil rabbitMQUtil;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Autowired(required = false)
    private InternalServiceTokenProvider internalServiceTokenProvider;

    // 不需要鉴权的路径
    private static final List<String> WHITE_LIST = List.of(
            "/api/users/login",
            "/api/users/register",
            "/api/users/refresh",
            "/api/users/password/forgot",
            "/api/users/password/reset",
            "/api/public/shares/**",
            "/actuator/health",
            "/ws/**"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 先清空客户端可能伪造的身份头与内部令牌头，防止身份伪装/越权攻击
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("X-User-Id");
            headers.remove("X-Username");
            headers.remove("X-Role");
            headers.remove("X-Organization-Id");
            headers.remove("X-Internal-Service-Token");
        }).build();
        exchange = exchange.mutate().request(request).build();
        final ServerWebExchange sanitizedExchange = exchange;
        String path = request.getPath().value();
        String method = request.getMethod().name();

        log.info("网关收到请求: {} {}", method, path);

        // 发送网关日志消息
        sendGatewayLog(path, method, null);

        // 检查是否在白名单中
        if (isWhiteList(path)) {
            return chain.filter(exchange);
        }

        // 从请求头中获取令牌
        HttpHeaders headers = request.getHeaders();
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("请求未授权: {} {}", method, path);
            sendGatewayAlert("UNAUTHORIZED", "缺少有效的认证令牌: " + path);
            return unauthorized(exchange);
        }

        // 提取令牌
        String token = authHeader.substring(7);

        // 验证令牌
        if (!JwtUtils.validateAccessToken(token)) {
            log.warn("令牌无效: {} {}", method, path);
            sendGatewayAlert("INVALID_TOKEN", "无效的访问令牌: " + path);
            return unauthorized(exchange);
        }

        // 令牌有效，将用户信息存储到请求头中
        try {
            Long userId = JwtUtils.getUserId(token);
            String username = JwtUtils.getUsername(token);
            String role = JwtUtils.getRole(token);

            // 从令牌取会话信息，与 Redis 中的会话归属和版本号比对，保证令牌未被注销或强制下线
            String sessionId = JwtUtils.getSessionId(token);
            long tokenVersion = JwtUtils.getSessionVersion(token);
            if (sessionId == null) return unauthorized(exchange);
            return redis.opsForValue().get("auth:session:" + sessionId).defaultIfEmpty("")
                .zipWith(redis.opsForValue().get("auth:user-version:" + userId).defaultIfEmpty("0"))
                .flatMap(values -> {
                    if (!String.valueOf(userId).equals(values.getT1()) || tokenVersion != parseVersion(values.getT2())) {
                        return unauthorized(sanitizedExchange);
                    }
                    // 会话校验通过：把已验证的用户身份写入可信请求头，转发给下游服务
                    ServerHttpRequest.Builder requestBuilder = request.mutate();
                    // Never trust identity headers supplied by the client.
                    requestBuilder.header("X-User-Id", userId.toString());
                    requestBuilder.header("X-Username", username);
                    if (role != null) {
                        requestBuilder.header("X-Role", role);
                    }
                    String trustedServiceToken = resolveInternalServiceToken();
                    if (trustedServiceToken != null && !trustedServiceToken.isBlank()) {
                        requestBuilder.header("X-Internal-Service-Token", trustedServiceToken);
                    }
            // 更新网关日志，添加用户ID
            sendGatewayLog(path, method, userId.toString());

                    ServerWebExchange authenticatedExchange = sanitizedExchange.mutate()
                            .request(requestBuilder.build())
                            .build();
                    return chain.filter(authenticatedExchange)
                            .doOnSuccess(ignored -> logUnexpectedResponse(path, method, userId, authenticatedExchange));
                });
        } catch (Exception e) {
            log.error("处理令牌时出错", e);
            sendGatewayAlert("TOKEN_ERROR", "处理访问令牌时出错: " + e.getMessage());
            return unauthorized(exchange);
        }
    }

    private long parseVersion(String value) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return 0L; }
    }

    /** Prefer the configured secret file so production Docker Secrets work; retain the property fallback for local dev/tests. */
    private String resolveInternalServiceToken() {
        if (internalServiceTokenProvider != null) {
            try {
                return internalServiceTokenProvider.getRequiredToken();
            } catch (IllegalStateException ignored) {
                // Keep local development behavior when no internal token is configured.
            }
        }
        return internalServiceToken == null ? null : internalServiceToken.trim();
    }

    private void logUnexpectedResponse(String path, String method, Long userId, ServerWebExchange exchange) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        if (status != null && !status.is2xxSuccessful()) {
            log.warn("下游服务响应异常: {} {} status={} userId={}", method, path, status.value(), userId);
        }
    }

    /**
     * 发送网关日志消息
     */
    private void sendGatewayLog(String path, String method, String userId) {
        Map<String, Object> message = new HashMap<>();
        message.put("path", path);
        message.put("method", method);
        message.put("userId", userId);
        message.put("timestamp", LocalDateTime.now().toString());
        
        try {
            log.info("发送网关日志消息到 RabbitMQ");
            rabbitMQUtil.send(RabbitMQConfig.GATEWAY_EXCHANGE, RabbitMQConfig.GATEWAY_LOG_ROUTING_KEY, message);
        } catch (Exception e) {
            log.warn("网关日志消息发送 RabbitMQ 失败，不影响当前请求: path={}, method={}, error={}",
                    path, method, e.getMessage(), e);
        }
    }

    /**
     * 发送网关告警消息
     */
    private void sendGatewayAlert(String alertType, String description) {
        Map<String, Object> message = new HashMap<>();
        message.put("alertType", alertType);
        message.put("description", description);
        message.put("timestamp", LocalDateTime.now().toString());
        
        try {
            log.info("发送网关告警消息到 RabbitMQ");
            rabbitMQUtil.send(RabbitMQConfig.GATEWAY_EXCHANGE, RabbitMQConfig.GATEWAY_ALERT_ROUTING_KEY, message);
        } catch (Exception e) {
            log.warn("网关告警消息发送 RabbitMQ 失败，不影响当前请求: alertType={}, error={}",
                    alertType, e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        // 过滤器执行顺序，数字越小优先级越高
        return -100;
    }

    /**
     * 检查路径是否在白名单中
     */
    private boolean isWhiteList(String path) {
        for (String whitePath : WHITE_LIST) {
            if (whitePath.endsWith("**")) {
                // 支持通配符匹配，如/api/users/**
                String prefix = whitePath.substring(0, whitePath.length() - 2);
                if (path.equals(prefix) || path.startsWith(prefix)) {
                    return true;
                }
            } else {
                // 精确匹配或前缀匹配
                if (path.equals(whitePath) || path.startsWith(whitePath + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 返回未授权响应
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

}
