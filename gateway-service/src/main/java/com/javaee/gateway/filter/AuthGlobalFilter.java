package com.javaee.gateway.filter;

import com.javaee.common.config.security.InternalServiceTokenProvider;
import com.javaee.gateway.config.RabbitMQConfig;
import com.javaee.gateway.security.GatewayPrincipal;
import com.javaee.gateway.util.RabbitMQUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Cleans client-controlled identity headers and forwards the identity created by
 * Spring Security's authenticated principal to downstream services.
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${security.internal-service-token:}")
    private String internalServiceToken;

    @Autowired
    private RabbitMQUtil rabbitMQUtil;

    @Autowired(required = false)
    private InternalServiceTokenProvider internalServiceTokenProvider;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("X-User-Id");
            headers.remove("X-Username");
            headers.remove("X-Role");
            headers.remove("X-Organization-Id");
            headers.remove("X-Internal-Service-Token");
        }).build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(request).build();
        String path = request.getPath().value();
        String method = request.getMethod().name();
        log.info("网关收到请求: {} {}", method, path);
        sendGatewayLog(path, method, null);

        return sanitizedExchange.getPrincipal()
                .flatMap(principal -> {
                    if (!(principal instanceof Authentication authentication)
                            || !(authentication.getPrincipal() instanceof GatewayPrincipal gatewayPrincipal)) {
                        return chain.filter(sanitizedExchange);
                    }

                    var requestBuilder = request.mutate()
                            .header("X-User-Id", String.valueOf(gatewayPrincipal.userId()))
                            .header("X-Username", gatewayPrincipal.username())
                            .header("X-Role", gatewayPrincipal.role());
                    String trustedServiceToken = resolveInternalServiceToken();
                    if (trustedServiceToken != null && !trustedServiceToken.isBlank()) {
                        requestBuilder.header("X-Internal-Service-Token", trustedServiceToken);
                    }
                    String userId = String.valueOf(gatewayPrincipal.userId());
                    sendGatewayLog(path, method, userId);
                    ServerWebExchange authenticatedExchange = sanitizedExchange.mutate()
                            .request(requestBuilder.build())
                            .build();
                    return chain.filter(authenticatedExchange)
                            .doOnSuccess(ignored -> logUnexpectedResponse(path, method,
                                    gatewayPrincipal.userId(), authenticatedExchange));
                })
                .switchIfEmpty(chain.filter(sanitizedExchange));
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
        var status = exchange.getResponse().getStatusCode();
        if (status != null && !status.is2xxSuccessful()) {
            log.warn("下游服务响应异常: {} {} status={} userId={}", method, path, status.value(), userId);
        }
    }

    private void sendGatewayLog(String path, String method, String userId) {
        Map<String, Object> message = new HashMap<>();
        message.put("path", path);
        message.put("method", method);
        message.put("userId", userId);
        message.put("timestamp", LocalDateTime.now().toString());
        try {
            rabbitMQUtil.send(RabbitMQConfig.GATEWAY_EXCHANGE, RabbitMQConfig.GATEWAY_LOG_ROUTING_KEY, message);
        } catch (Exception exception) {
            log.warn("网关日志消息发送 RabbitMQ 失败，不影响当前请求: path={}, method={}, error={}",
                    path, method, exception.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
