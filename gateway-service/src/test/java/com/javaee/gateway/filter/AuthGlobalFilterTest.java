package com.javaee.gateway.filter;

import com.javaee.gateway.util.RabbitMQUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class AuthGlobalFilterTest {
    private AuthGlobalFilter filter;
    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> values;

    @BeforeEach
    void setUp() {
        filter = new AuthGlobalFilter();
        ReflectionTestUtils.setField(filter, "rabbitMQUtil", mock(RabbitMQUtil.class));
        redis = mock(ReactiveStringRedisTemplate.class);
        values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(filter, "redis", redis);
        setField(filter, "internalServiceToken", "internal-service-secret");
        System.setProperty("smartdoc.jwt.keys", "current:current-key-material-123456789012345678901234567890");
        System.setProperty("smartdoc.jwt.active-kid", "current");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("smartdoc.jwt.keys");
        System.clearProperty("smartdoc.jwt.active-kid");
    }

    @Test
    void publicRouteStillRemovesClientSuppliedIdentityHeaders() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/users/login")
                .header("X-User-Id", "999")
                .header("X-Username", "attacker")
                .header("X-Role", "ADMIN")
                .header("X-Organization-Id", "other-company")
                .header("X-Internal-Service-Token", "forged"));
        AtomicReference<org.springframework.web.server.ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = value -> {
            forwarded.set(value);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
        var headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isNull();
        assertThat(headers.getFirst("X-Username")).isNull();
        assertThat(headers.getFirst("X-Role")).isNull();
        assertThat(headers.getFirst("X-Organization-Id")).isNull();
        assertThat(headers.getFirst("X-Internal-Service-Token")).isNull();
    }

    @Test
    void protectedRouteWithoutBearerTokenReturnsUnauthorized() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents"));
        GatewayFilterChain chain = ignored -> Mono.error(new AssertionError("chain must not run"));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validTokenReplacesForgedIdentityHeadersAndInjectsTrustedIdentity() {
        String token = com.javaee.common.utils.JwtUtils.generateToken(7L, "member", "USER", "sid-1", 3);
        when(values.get("auth:session:sid-1")).thenReturn(Mono.just("7"));
        when(values.get("auth:user-version:7")).thenReturn(Mono.just("3"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "999")
                .header("X-Role", "ADMIN"));
        AtomicReference<org.springframework.web.server.ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, value -> {
            forwarded.set(value);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        var headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("7");
        assertThat(headers.getFirst("X-Username")).isEqualTo("member");
        assertThat(headers.getFirst("X-Role")).isEqualTo("USER");
        assertThat(headers.getFirst("X-Internal-Service-Token")).isEqualTo("internal-service-secret");
    }

    @Test
    void revokedSessionIsRejectedEvenWhenJwtSignatureIsValid() {
        String token = com.javaee.common.utils.JwtUtils.generateToken(7L, "member", "USER", "sid-1", 3);
        when(values.get("auth:session:sid-1")).thenReturn(Mono.empty());
        when(values.get("auth:user-version:7")).thenReturn(Mono.just("3"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents")
                .header("Authorization", "Bearer " + token));
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.filter(exchange, value -> {
            invoked.set(true);
            return Mono.empty();
        }).block();

        assertThat(invoked.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void oldSessionVersionIsRejectedAfterForceLogout() {
        String token = com.javaee.common.utils.JwtUtils.generateToken(7L, "member", "USER", "sid-1", 2);
        when(values.get("auth:session:sid-1")).thenReturn(Mono.just("7"));
        when(values.get("auth:user-version:7")).thenReturn(Mono.just("3"));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents")
                .header("Authorization", "Bearer " + token));

        filter.filter(exchange, value -> Mono.error(new AssertionError("revoked token must not reach downstream"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wildcardPublicShareRouteIsAllowed() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/public/shares/token-1/content"));
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.filter(exchange, value -> {
            invoked.set(true);
            return Mono.empty();
        }).block();

        assertThat(invoked.get()).isTrue();
    }
}
