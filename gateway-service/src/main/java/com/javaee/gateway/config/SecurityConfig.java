package com.javaee.gateway.config;

import com.javaee.gateway.security.BearerTokenServerAuthenticationConverter;
import com.javaee.gateway.security.GatewayAuthenticationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                             GatewayAuthenticationManager authenticationManager) {
        AuthenticationWebFilter authenticationFilter = new AuthenticationWebFilter(authenticationManager);
        authenticationFilter.setServerAuthenticationConverter(new BearerTokenServerAuthenticationConverter());
        authenticationFilter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        authenticationFilter.setAuthenticationFailureHandler((exchange, exception) -> {
            exchange.getExchange().getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getExchange().getResponse().setComplete();
        });

        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .addFilterAt(authenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/users/register", "/api/users/login", "/api/users/refresh",
                                "/api/users/password/forgot", "/api/users/password/reset",
                                "/api/public/shares/**", "/actuator/health", "/ws/**").permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((exchange, exception) -> {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }));
        return http.build();
    }
}
