package com.javaee.gateway.config;

import com.javaee.gateway.security.GatewayAuthenticationManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest
@AutoConfigureWebTestClient
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private GatewayAuthenticationManager authenticationManager;

    @Test
    void protectedRouteRequiresAuthenticationButPublicAndSockJsRoutesRemainReachable() {
        webTestClient.get().uri("/api/documents")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.post().uri("/api/users/login")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));

        webTestClient.get().uri("/api/public/shares/token/content")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));

        webTestClient.get().uri("/ws/agent/info")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }
}
