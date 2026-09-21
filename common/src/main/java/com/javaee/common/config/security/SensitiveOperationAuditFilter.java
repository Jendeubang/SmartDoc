package com.javaee.common.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 【简历第6条 · 敏感操作审计】审计过滤器：对登录、密码、分享、AI/Agent/RAG 等敏感接口，
// 在请求结束后输出结构化风险日志（不含请求体，避免泄漏敏感信息），用于安全审计与追溯。
/** Emits body-free, structured risk events for security-sensitive endpoints. */
@Component
public class SensitiveOperationAuditFilter extends OncePerRequestFilter {
    private static final Logger riskLog = LoggerFactory.getLogger("SECURITY_RISK");

    // 仅对敏感路径（登录、密码、分享、AI/Agent/RAG）生效，其余请求不审计
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.contains("/login") || path.contains("/password/") ||
                path.contains("/shares") || path.startsWith("/api/ai") ||
                path.startsWith("/api/agent") || path.startsWith("/api/rag"));
    }

    @Override
    // 放行请求并在结束后输出结构化审计日志，记录方法、路径、状态、用户、IP 与耗时
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long started = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            riskLog.info("event=sensitive_api method={} path={} status={} userId={} organizationId={} ip={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    safe(request.getHeader("X-User-Id")), safe(request.getHeader("X-Organization-Id")),
                    clientIp(request), System.currentTimeMillis() - started);
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "anonymous" : value.replaceAll("[\\r\\n]", "");
    }
}
