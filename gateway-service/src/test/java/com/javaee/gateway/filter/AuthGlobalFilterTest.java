package com.javaee.gateway.filter;

import com.javaee.gateway.security.GatewayPrincipal;
import com.javaee.gateway.util.RabbitMQUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class AuthGlobalFilterTest {
    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthGlobalFilter();
        setField(filter, "rabbitMQUtil", mock(RabbitMQUtil.class));
        setField(filter, "internalServiceToken", "internal-service-secret");
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

        filter.filter(exchange, value -> {
            forwarded.set(value);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        var headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isNull();
        assertThat(headers.getFirst("X-Username")).isNull();
        assertThat(headers.getFirst("X-Role")).isNull();
        assertThat(headers.getFirst("X-Organization-Id")).isNull();
        assertThat(headers.getFirst("X-Internal-Service-Token")).isNull();
    }

    @Test
    void unauthenticatedRequestIsLeftForSpringSecurityAuthorization() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents"));
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.filter(exchange, value -> {
            invoked.set(true);
            return Mono.empty();
        }).block();

        assertThat(invoked.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void authenticatedPrincipalReplacesForgedHeadersAndInjectsTrustedIdentity() {
        var authentication = new UsernamePasswordAuthenticationToken(
                new GatewayPrincipal(7L, "member", "USER"),
                "token",
                List.of());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents")
                .header("X-User-Id", "999")
                .header("X-Role", "ADMIN"))
                .mutate()
                .principal(Mono.just((Principal) authentication))
                .build();
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
    void wildcardPublicShareRouteIsAllowedAndSanitized() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/public/shares/token-1/content")
                .header("X-Role", "ADMIN"));
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.filter(exchange, value -> {
            invoked.set(true);
            assertThat(value.getRequest().getHeaders().getFirst("X-Role")).isNull();
            return Mono.empty();
        }).block();

        assertThat(invoked.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
