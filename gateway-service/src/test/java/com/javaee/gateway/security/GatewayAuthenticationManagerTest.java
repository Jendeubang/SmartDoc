package com.javaee.gateway.security;

import com.javaee.common.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayAuthenticationManagerTest {

    @Mock
    private ReactiveStringRedisTemplate redis;

    @Mock
    private ReactiveValueOperations<String, String> values;

    private GatewayAuthenticationManager manager;

    @BeforeEach
    void setUp() {
        manager = new GatewayAuthenticationManager(redis);
        System.setProperty("smartdoc.jwt.keys", "current:current-key-material-123456789012345678901234567890");
        System.setProperty("smartdoc.jwt.active-kid", "current");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("smartdoc.jwt.keys");
        System.clearProperty("smartdoc.jwt.active-kid");
    }

    @Test
    void acceptsOnlyActiveAccessSession() {
        String token = JwtUtils.generateToken(7L, "member", "USER", "sid-1", 3);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("auth:session:sid-1")).thenReturn(Mono.just("7"));
        when(values.get("auth:user-version:7")).thenReturn(Mono.just("3"));

        Authentication result = manager.authenticate(
                new UsernamePasswordAuthenticationToken("Bearer", token)).block();

        assertThat(result).isNotNull();
        assertThat(result.getPrincipal()).isEqualTo(new GatewayPrincipal(7L, "member", "USER"));
    }

    @Test
    void rejectsRefreshTokenAndRevokedSession() {
        String refresh = JwtUtils.generateRefreshToken(7L, "sid-1", 3);

        assertThatThrownBy(() -> manager.authenticate(
                new UsernamePasswordAuthenticationToken("Bearer", refresh)).block())
                .isInstanceOf(BadCredentialsException.class);
    }
}
