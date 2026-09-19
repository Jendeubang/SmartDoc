package com.javaee.fileservice.security;

import com.javaee.common.config.security.TenantContext;
import com.javaee.common.config.security.SessionTokenValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Validates X-Organization-Id against the authenticated user's active membership. */
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
        String userId = authenticatedUserId(request);
        if (!organizationId.matches("[A-Za-z0-9-]{1,64}") || userId == null || !isActiveMember(organizationId, userId)) {
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

    private String authenticatedUserId(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            try { return String.valueOf(sessions.requireActiveAccessToken(token)); }
            catch (SecurityException ignored) { return null; }
        }
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken || authentication.getPrincipal() == null) return null;
        String value = authentication.getPrincipal().toString();
        return value.matches("\\d+") ? value : null;
    }

    private boolean isActiveMember(String organizationId, String userId) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM enterprise_member WHERE organization_id=? AND user_id=? AND status='active'",
                    Integer.class, organizationId, Long.valueOf(userId));
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
