package com.javaee.aiservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * When the gateway has already validated the JWT, rebuilds the authenticated
 * principal from the gateway's signed internal identity headers. The internal
 * token is never accepted from the browser and is injected only by the gateway.
 */
@Component
public class TrustedGatewayAuthenticationFilter extends OncePerRequestFilter {

    @Value("${security.internal-service-token:}")
    private String internalServiceToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String suppliedToken = request.getHeader("X-Internal-Service-Token");
            String userId = request.getHeader("X-User-Id");
            if (isTrusted(suppliedToken) && userId != null && userId.matches("\\d{1,19}")) {
                String role = request.getHeader("X-Role");
                role = role == null || role.isBlank() ? "user" : role.trim();
                if (role.startsWith("ROLE_")) role = role.substring(5);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                Long.valueOf(userId), null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isTrusted(String supplied) {
        if (internalServiceToken == null || internalServiceToken.isBlank() || supplied == null) return false;
        return MessageDigest.isEqual(
                internalServiceToken.trim().getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
