package com.javaee.common.config.security;

import com.javaee.common.utils.JwtUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Validates both JWT integrity and the server-side session/version state. */
@Component
public class SessionTokenValidator {
    private final StringRedisTemplate redis;

    public SessionTokenValidator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Long requireActiveAccessToken(String token) {
        if (token == null || !JwtUtils.validateAccessToken(token)) {
            throw new SecurityException("Invalid access token");
        }
        Long userId = JwtUtils.getUserId(token);
        String sessionId = JwtUtils.getSessionId(token);
        if (sessionId == null || sessionId.isBlank()) throw new SecurityException("Missing session");
        String owner = redis.opsForValue().get("auth:session:" + sessionId);
        String version = redis.opsForValue().get("auth:user-version:" + userId);
        if (!String.valueOf(userId).equals(owner)
                || JwtUtils.getSessionVersion(token) != parseVersion(version)) {
            throw new SecurityException("Session has been revoked");
        }
        return userId;
    }

    private long parseVersion(String value) {
        try { return value == null ? 0L : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return -1L; }
    }
}
