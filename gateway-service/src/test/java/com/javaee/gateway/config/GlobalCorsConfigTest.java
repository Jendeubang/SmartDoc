package com.javaee.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.cors.reactive.CorsWebFilter;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalCorsConfigTest {

    @Test
    void allowsConfiguredOriginAndRejectsUnknownOrigin() {
        GlobalCorsConfig config = new GlobalCorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
        CorsWebFilter filter = config.corsWebFilter();
        WebTestClient client = WebTestClient.bindToWebHandler(exchange ->
                filter.filter(exchange, ignored -> Mono.empty())).build();

        client.options().uri("http://localhost/api/documents")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");

        client.options().uri("http://localhost/api/documents")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void doesNotAllowCredentialsOrWildcardHeaders() {
        GlobalCorsConfig config = new GlobalCorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
        CorsWebFilter filter = config.corsWebFilter();
        WebTestClient client = WebTestClient.bindToWebHandler(exchange ->
                filter.filter(exchange, ignored -> Mono.empty())).build();

        client.options().uri("http://localhost/api/documents")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectHeader().value("Access-Control-Allow-Headers", value -> {
                    assertThat(value).contains("Authorization");
                    assertThat(value).doesNotContain("*");
                })
                .expectHeader().doesNotExist("Access-Control-Allow-Credentials");
    }

    @Test
    void productionProfileRejectsAnEmptyAllowList() {
        GlobalCorsConfig config = new GlobalCorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", " ");
        ReflectionTestUtils.setField(config, "activeProfiles", "prod");

        assertThatThrownBy(config::corsWebFilter)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-origins");
    }
}
