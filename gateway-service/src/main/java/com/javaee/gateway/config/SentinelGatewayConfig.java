package com.javaee.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * 网关限流配置
 *
 * 使用 Spring Cloud Gateway 内置 RedisRateLimiter + 令牌桶算法：
 *   - 每个 IP 独立的 QPS 配额
 *   - 超出配额返回 HTTP 429
 *
 * 不依赖 Sentinel Dashboard，纯配置 + Redis 即可工作。
 */
@Configuration
public class SentinelGatewayConfig {

    /**
     * 常规页面接口：30 req/s，突发 60。
     * 打开一个文档会并行请求详情、协作者和预览资源，10/s 容易让正常页面请求互相抢占令牌。
     */
    public static final int DEFAULT_REPLENISH_RATE = 30;
    public static final int DEFAULT_BURST_CAPACITY = 60;

    /** AI 路由: 5 req/s, 突发 10（大模型调用昂贵） */
    public static final int AI_REPLENISH_RATE = 5;
    public static final int AI_BURST_CAPACITY = 10;

    /** AI 轮询、模型列表和观测指标：不与模型生成争抢配额。 */
    public static final int AI_READ_REPLENISH_RATE = 30;
    public static final int AI_READ_BURST_CAPACITY = 60;

    // ── 限流器 ──────────────────────────────────────────────

    @Primary
    @Bean("gatewayRateLimiter")
    public RedisRateLimiter gatewayRateLimiter() {
        return new RedisRateLimiter(DEFAULT_REPLENISH_RATE, DEFAULT_BURST_CAPACITY);
    }

    @Bean("aiRateLimiter")
    public RedisRateLimiter aiRateLimiter() {
        return new RedisRateLimiter(AI_REPLENISH_RATE, AI_BURST_CAPACITY);
    }

    @Bean("aiReadRateLimiter")
    public RedisRateLimiter aiReadRateLimiter() {
        return new RedisRateLimiter(AI_READ_REPLENISH_RATE, AI_READ_BURST_CAPACITY);
    }

    // ── Key 解析器 ──────────────────────────────────────────

    /** 按客户端 IP 限流（默认） */
    @Primary
    @Bean("ipKeyResolver")
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return Mono.just("rl:" + forwarded.split(",")[0].trim());
            }
            String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return Mono.just("rl:" + realIp.trim());
            }
            var remote = exchange.getRequest().getRemoteAddress();
            String ip = remote != null ? remote.getAddress().getHostAddress() : "unknown";
            return Mono.just("rl:" + ip);
        };
    }

    /** 已登录请求按用户 ID 限流；匿名请求退化为按 IP 限流。 */
    @Bean("userKeyResolver")
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("rl:u:" + userId);
            }
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return Mono.just("rl:ip:" + forwarded.split(",")[0].trim());
            }
            var remote = exchange.getRequest().getRemoteAddress();
            String key = remote != null ? "rl:ip:" + remote.getAddress().getHostAddress() : "rl:anonymous";
            return Mono.just(key);
        };
    }
}
