package com.javaee.gateway.security;

import com.javaee.common.utils.JwtUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

@Component
public class GatewayAuthenticationManager implements ReactiveAuthenticationManager {

    private final ReactiveStringRedisTemplate redis;

    public GatewayAuthenticationManager(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String token = authentication.getCredentials() == null
                ? ""
                : String.valueOf(authentication.getCredentials());
        if (!JwtUtils.validateAccessToken(token)) {
            return Mono.error(new BadCredentialsException("Invalid access token"));
        }

        try {
            Long userId = JwtUtils.getUserId(token);
            String sessionId = JwtUtils.getSessionId(token);
            if (userId == null || sessionId == null || sessionId.isBlank()) {
                return Mono.error(new BadCredentialsException("Access token has no active session"));
            }
            String username = JwtUtils.getUsername(token);
            String role = JwtUtils.getRole(token);
            String normalizedRole = role == null || role.isBlank()
                    ? "USER"
                    : role.toUpperCase(Locale.ROOT);
            long tokenVersion = JwtUtils.getSessionVersion(token);
            return redis.opsForValue().get("auth:session:" + sessionId)
                    .defaultIfEmpty("")
                    .zipWith(redis.opsForValue().get("auth:user-version:" + userId).defaultIfEmpty("0"))
                    .flatMap(values -> {
                        if (!String.valueOf(userId).equals(values.getT1())
                                || tokenVersion != parseVersion(values.getT2())) {
                            return Mono.error(new BadCredentialsException("Session has been revoked"));
                        }
                        GatewayPrincipal principal = new GatewayPrincipal(userId, username, normalizedRole);
                        return Mono.just(new UsernamePasswordAuthenticationToken(
                                principal,
                                token,
                                List.of(new SimpleGrantedAuthority("ROLE_" + normalizedRole))));
                    });
        } catch (Exception exception) {
            return Mono.error(new BadCredentialsException("Invalid access token", exception));
        }
    }

    private long parseVersion(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
