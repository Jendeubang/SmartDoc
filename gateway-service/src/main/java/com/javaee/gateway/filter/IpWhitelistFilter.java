package com.javaee.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * IP 白名单全局过滤器
 * 如果配置了白名单，仅允许白名单内的 IP 或 IP 段访问网关
 * 不在白名单中的请求返回 403 JSON 响应
 *
 * 配置示例：
 *   gateway.ip.whitelist.enabled=true
 *   gateway.ip.whitelist.addrs=192.168.1.0/24,10.0.0.1,127.0.0.1
 */
@Slf4j
@Component
public class IpWhitelistFilter implements GlobalFilter, Ordered {

    @Value("${gateway.ip.whitelist.enabled:false}")
    private boolean enabled;

    @Value("${gateway.ip.whitelist.addrs:}")
    private String whitelistAddrs;

    private static final String ERROR_BODY = "{\"code\":403,\"msg\":\"IP not allowed\"}";

    /** 优先级高于 AuthGlobalFilter，在白名单检查之后才进行鉴权 */
    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String clientIp = extractClientIp(exchange.getRequest());
        List<String> allowed = parseWhitelist();

        if (allowed.isEmpty()) {
            log.warn("IP 白名单已启用但未配置任何地址，所有请求将被拒绝");
            return forbidden(exchange.getResponse());
        }

        if (isAllowed(clientIp, allowed)) {
            return chain.filter(exchange);
        }

        log.warn("IP 不在白名单中，拒绝访问: ip={}, path={}", clientIp, exchange.getRequest().getPath().value());
        return forbidden(exchange.getResponse());
    }

    /** 从请求中提取客户端真实 IP */
    private String extractClientIp(ServerHttpRequest request) {
        // X-Forwarded-For（取第一个，即原始客户端 IP）
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        // X-Real-IP
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        // 直连 IP
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    /** 解析配置的白名单地址列表 */
    private List<String> parseWhitelist() {
        if (whitelistAddrs == null || whitelistAddrs.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(whitelistAddrs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 检查 IP 是否在白名单中（支持 CIDR） */
    private boolean isAllowed(String ip, List<String> allowed) {
        for (String entry : allowed) {
            if (entry.contains("/")) {
                // CIDR 网段匹配
                if (matchCidr(ip, entry)) {
                    return true;
                }
            } else {
                // 精确匹配
                if (entry.equals(ip)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** CIDR 网段匹配（如 192.168.1.0/24） */
    private boolean matchCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            byte[] netBytes = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);

            if (ipBytes.length != netBytes.length) {
                return false;
            }

            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;

            // 比较完整字节
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != netBytes[i]) {
                    return false;
                }
            }

            // 比较剩余位
            if (remainingBits > 0 && fullBytes < ipBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((ipBytes[fullBytes] & mask) != (netBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            log.warn("CIDR 匹配失败: ip={}, cidr={}, error={}", ip, cidr, e.getMessage());
            return false;
        }
    }

    /** 返回 403 JSON 响应 */
    private Mono<Void> forbidden(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ERROR_BODY.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
