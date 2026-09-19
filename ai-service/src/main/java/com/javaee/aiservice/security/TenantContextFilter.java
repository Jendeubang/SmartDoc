package com.javaee.aiservice.security;

import com.javaee.common.config.security.SessionTokenValidator;
import com.javaee.common.config.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Establishes an organization context only after JWT and membership validation. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class TenantContextFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final SessionTokenValidator sessions;

    public TenantContextFilter(JdbcTemplate jdbc, SessionTokenValidator sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String organizationId = request.getHeader("X-Organization-Id");
        if (organizationId == null || organizationId.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        Long userId = accessUserId(request);
        if (!organizationId.matches("[A-Za-z0-9-]{1,64}") || userId == null || !isMember(organizationId, userId)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid organization context");
            return;
        }
        try {
            TenantContext.set(organizationId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Long accessUserId(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        try { return sessions.requireActiveAccessToken(authorization.substring(7)); }
        catch (SecurityException ignored) { return null; }
    }

    private boolean isMember(String organizationId, Long userId) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM enterprise_member WHERE organization_id=? AND user_id=? AND status='active'",
                    Integer.class, organizationId, userId);
            return count != null && count > 0;
        } catch (Exception ignored) { return false; }
    }
}
