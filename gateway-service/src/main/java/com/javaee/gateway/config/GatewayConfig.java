package com.javaee.gateway.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由配置
 * 支持服务发现模式（lb://）和直连模式（http://）
 *
 * 每个 REST 路由均启用 Sentinel + Redis 令牌桶限流：
 *   - 默认路由: 10 req/s, 突发 20
 *   - AI 路由:   5 req/s, 突发 10（大模型调用成本更高）
 *   - WebSocket 路由不限流
 *
 * StripPrefix/rewritePath 可按需启用：取消 filters 链的注释即可。
 */
@Configuration
public class GatewayConfig {

    // ──────────── 服务地址 ────────────
    @Value("${gateway.route.user-uri:lb://user}")
    private String userServiceUri;

    @Value("${gateway.route.file-uri:lb://file}")
    private String fileServiceUri;

    @Value("${gateway.route.ai-uri:lb://ai}")
    private String aiServiceUri;

    @Value("${gateway.route.ai-ws-uri:lb:ws://ai}")
    private String aiWebSocketUri;

    @Value("${gateway.route.document-uri:lb://document}")
    private String documentServiceUri;

    @Value("${gateway.route.document-ws-uri:http://localhost:8084}")
    private String documentWebSocketUri;

    // ──────────── 限流组件 ────────────
    @Autowired
    @Qualifier("gatewayRateLimiter")
    private RedisRateLimiter defaultRateLimiter;

    @Autowired
    @Qualifier("aiRateLimiter")
    private RedisRateLimiter aiRateLimiter;

    @Autowired
    @Qualifier("aiReadRateLimiter")
    private RedisRateLimiter aiReadRateLimiter;

    @Autowired
    @Qualifier("userKeyResolver")
    private KeyResolver userKeyResolver;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // ── 用户服务 ──────────────────────────────────────────
                .route("user", r -> r
                        .path("/api/users/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(defaultRateLimiter)
                                        .setKeyResolver(userKeyResolver)))
                        .uri(userServiceUri))

                // ── 文件服务 ──────────────────────────────────────────
                .route("file", r -> r
                        .path("/api/files/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(defaultRateLimiter)
                                        .setKeyResolver(userKeyResolver)))
                        .uri(fileServiceUri))

                // ── AI 页面基础设施 ──────────────────────────────────
                // 轮询/指标不会消耗模型生成配额，避免打开页面时误触发 429。
                .route("ai-read", r -> r
                        .path("/api/ai/aiops/**", "/api/ai/async/jobs/**", "/api/ai/models",
                                "/api/ai/provider-credentials/**", "/api/ai/agent/conversations/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(aiReadRateLimiter)
                                        .setKeyResolver(userKeyResolver)))
                        .uri(aiServiceUri))

                // ── AI 服务 (REST) ────────────────────────────────────
                .route("ai", r -> r
                        .path("/api/ai/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(aiRateLimiter)
                                        .setKeyResolver(userKeyResolver)))
                        .uri(aiServiceUri))

                // ── Agent / Skill 独立接口 ────────────────────────────
                .route("ai-skills", r -> r
                        .path("/api/skills/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(aiRateLimiter)
                                        .setKeyResolver(userKeyResolver)))
                        .uri(aiServiceUri))

                // ── Agent 工作台 WebSocket（不限流） ──────────────────
                .route("ai-agent-ws", r -> r
                        .path("/ws/agent/**")
                        .uri(aiWebSocketUri))

                // ── 文档服务 ──────────────────────────────────────────
                .route("document", r -> r
                        .path("/api/documents/**", "/api/enterprise/**", "/api/public/shares/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(defaultRateLimiter)
                                        .setKeyResolver(userKeyResolver)))
                        .uri(documentServiceUri))

                // ── 文档协作 WebSocket（不限流） ──────────────────────
                .route("document-ws", r -> r
                        .path("/ws/collaborate/**")
                        .uri(documentWebSocketUri))

                .build();
    }

}
