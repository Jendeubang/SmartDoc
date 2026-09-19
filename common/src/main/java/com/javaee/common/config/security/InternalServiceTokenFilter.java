package com.javaee.common.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

// 【简历第6条 · 内部服务令牌】只拦截 /api/internal/** 端点，校验 X-Internal-Service-Token 请求头，
// 用常量时间比较（MessageDigest.isEqual）防止时序攻击，令牌不符则返回 401。
/** Guards internal-only endpoints with a service-to-service secret. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class InternalServiceTokenFilter extends OncePerRequestFilter {
    @Value("${security.internal-service-token:}")
    private String expected;
    @Value("${security.internal-service-token-file:}")
    private String expectedFile;

    // 仅拦截内部服务接口（/api/internal/**），其余请求直接放行
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    // 常量时间比对请求头令牌与配置令牌，未配置或不一致时拒绝请求
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Internal-Service-Token");
        String configured = configuredToken();
        if (configured == null || configured.isBlank() || supplied == null ||
                !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    // 从配置项或密钥文件读取服务间令牌
    private String configuredToken() {
        if (expected != null && !expected.isBlank()) return expected.trim();
        if (expectedFile == null || expectedFile.isBlank()) return null;
        try { return Files.readString(Path.of(expectedFile.trim()), StandardCharsets.UTF_8).trim(); }
        catch (IOException exception) { throw new IllegalStateException("Unable to read internal service token secret", exception); }
    }
}
