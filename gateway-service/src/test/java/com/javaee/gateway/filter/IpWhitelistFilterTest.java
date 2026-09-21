package com.javaee.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class IpWhitelistFilterTest {

    @Test
    void disabledWhitelistPassesRequestThrough() {
        IpWhitelistFilter filter = configured(false, "");
        AtomicBoolean invoked = new AtomicBoolean();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents"));

        filter.filter(exchange, passingChain(invoked)).block();

        assertThat(invoked).isTrue();
    }

    @Test
    void cidrAllowsForwardedClientAddress() {
        IpWhitelistFilter filter = configured(true, "10.10.0.0/16,127.0.0.1");
        AtomicBoolean invoked = new AtomicBoolean();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents")
                .header("X-Forwarded-For", "10.10.8.21, 172.18.0.2"));

        filter.filter(exchange, passingChain(invoked)).block();

        assertThat(invoked).isTrue();
    }

    @Test
    void addressOutsideWhitelistGetsForbiddenJsonResponse() {
        IpWhitelistFilter filter = configured(true, "10.10.0.0/16");
        AtomicBoolean invoked = new AtomicBoolean();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents")
                .header("X-Real-IP", "192.168.1.15"));

        filter.filter(exchange, passingChain(invoked)).block();

        assertThat(invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).isEqualTo("application/json");
    }

    private IpWhitelistFilter configured(boolean enabled, String addresses) {
        IpWhitelistFilter filter = new IpWhitelistFilter();
        ReflectionTestUtils.setField(filter, "enabled", enabled);
        ReflectionTestUtils.setField(filter, "whitelistAddrs", addresses);
        return filter;
    }

    private GatewayFilterChain passingChain(AtomicBoolean invoked) {
        return exchange -> {
            invoked.set(true);
            return Mono.empty();
        };
    }
}
