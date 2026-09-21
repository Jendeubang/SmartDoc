package com.javaee.user.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.utils.JwtUtils;
import com.javaee.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Regression tests for the Redis-backed access/refresh-token session protocol.
 * These tests intentionally use mocks so they run in every build without a
 * local Redis instance; the key names and token transitions mirror production.
 */
class AuthSessionServiceTest {
    private static final String KEYS = "current:current-key-material-123456789012345678901234567890";
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private AuthSessionService sessions;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        System.setProperty("smartdoc.jwt.keys", KEYS);
        System.setProperty("smartdoc.jwt.active-kid", "current");
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        doReturn(1L, 0L).when(redis).execute(any(), anyList(), any(), any(), any());
        sessions = new AuthSessionService(redis);
        user = new User();
        user.setId(7L);
        user.setUsername("member");
        user.setRole("USER");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("smartdoc.jwt.keys");
        System.clearProperty("smartdoc.jwt.active-kid");
    }

    @Test
    void loginPersistsRefreshTokenHashAndBindsBothTokensToOneSession() {
        when(values.get("auth:user-version:7")).thenReturn("0");

        var login = sessions.createSession(user);

        assertThat(JwtUtils.validateAccessToken(login.getAccessToken())).isTrue();
        assertThat(JwtUtils.validateRefreshToken(login.getRefreshToken())).isTrue();
        assertThat(JwtUtils.getSessionId(login.getAccessToken()))
                .isEqualTo(JwtUtils.getSessionId(login.getRefreshToken()));
        verify(values).set(eq("auth:refresh:" + JwtUtils.getSessionId(login.getRefreshToken())),
                eq(JwtUtils.sha256(login.getRefreshToken())), any());
        verify(sets).add(eq("auth:user-sessions:7"), eq(JwtUtils.getSessionId(login.getRefreshToken())));
    }

    @Test
    void refreshRotatesTokenAndRejectsReplayOfOldRefreshToken() {
        String oldRefresh = JwtUtils.generateRefreshToken(7L, "sid-1", 0);
        String newRefresh = JwtUtils.generateRefreshToken(7L, "sid-1", 0);
        when(values.get("auth:session:sid-1")).thenReturn("7");
        when(values.get("auth:user-version:7")).thenReturn("0");

        var rotated = sessions.rotate(user, oldRefresh);

        assertThat(rotated.getRefreshToken()).isNotEqualTo(oldRefresh);
        assertThat(JwtUtils.validateRefreshToken(rotated.getRefreshToken())).isTrue();

        assertThatThrownBy(() -> sessions.rotate(user, oldRefresh))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("刷新令牌已失效");
    }

    @Test
    void logoutRemovesSessionAndRefreshHash() {
        String access = JwtUtils.generateToken(7L, "member", "USER", "sid-1", 0);
        when(values.get("auth:session:sid-1")).thenReturn("7");

        sessions.logout(access, null);

        verify(redis).delete(Set.of("auth:session:sid-1", "auth:refresh:sid-1"));
        verify(sets).remove("auth:user-sessions:7", "sid-1");
    }

    @Test
    void forceLogoutBumpsUserVersionAndRemovesEveryKnownSession() {
        when(sets.members("auth:user-sessions:7")).thenReturn(Set.of("sid-1", "sid-2"));
        when(values.get("auth:session:sid-1")).thenReturn("7");
        when(values.get("auth:session:sid-2")).thenReturn("7");

        sessions.revokeAll(7L);

        verify(values).increment("auth:user-version:7");
        verify(redis).delete(Set.of("auth:session:sid-1", "auth:refresh:sid-1"));
        verify(redis).delete(Set.of("auth:session:sid-2", "auth:refresh:sid-2"));
        verify(redis).delete("auth:user-sessions:7");
    }
}
