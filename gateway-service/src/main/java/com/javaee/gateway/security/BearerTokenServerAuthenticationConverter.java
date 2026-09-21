package com.javaee.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class BearerTokenServerAuthenticationConverter implements ServerAuthenticationConverter {

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return Mono.empty();
        }
        if (!header.startsWith("Bearer ")) {
            return Mono.error(new BadCredentialsException("Authorization must use the Bearer scheme"));
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return Mono.error(new BadCredentialsException("Bearer token is empty"));
        }
        return Mono.just(new UsernamePasswordAuthenticationToken("Bearer", token));
    }
}
