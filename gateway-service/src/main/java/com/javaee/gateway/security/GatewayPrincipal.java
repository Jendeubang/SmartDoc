package com.javaee.gateway.security;

public record GatewayPrincipal(Long userId, String username, String role) {
}
