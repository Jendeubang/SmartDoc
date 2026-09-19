package com.javaee.common.config.security;

import com.javaee.common.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionTokenValidatorTest {
    private static final String KEYS = "current:current-key-material-123456789012345678901234567890";
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        System.setProperty("smartdoc.jwt.keys", KEYS);
        System.setProperty("smartdoc.jwt.active-kid", "current");
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
    }

    @AfterEach void cleanup() {
        System.clearProperty("smartdoc.jwt.keys");
        System.clearProperty("smartdoc.jwt.active-kid");
    }

    @Test void acceptsOnlyActiveServerSideSession() {
        String token = JwtUtils.generateToken(7L, "user", "USER", "sid-1", 3);
        when(values.get("auth:session:sid-1")).thenReturn("7");
        when(values.get("auth:user-version:7")).thenReturn("3");
        assertThat(new SessionTokenValidator(redis).requireActiveAccessToken(token)).isEqualTo(7L);
    }

    @Test void rejectsRevokedOrOldSessionVersion() {
        String token = JwtUtils.generateToken(7L, "user", "USER", "sid-1", 2);
        when(values.get("auth:session:sid-1")).thenReturn("7");
        when(values.get("auth:user-version:7")).thenReturn("3");
        assertThatThrownBy(() -> new SessionTokenValidator(redis).requireActiveAccessToken(token))
                .isInstanceOf(SecurityException.class).hasMessageContaining("revoked");
    }
}
