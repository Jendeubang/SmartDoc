package com.javaee.aiservice.config;

import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.aiservice.security.TrustedGatewayAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final TrustedGatewayAuthenticationFilter trustedGatewayAuthenticationFilter;
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          TrustedGatewayAuthenticationFilter trustedGatewayAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.trustedGatewayAuthenticationFilter = trustedGatewayAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/internal/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/error", "/", "/index.html",
                        "/agent-workbench.html", "/static/**", "/ws/**").permitAll()
                .requestMatchers("/actuator/**", "/swagger-ui.html", "/swagger-ui/**",
                        "/v3/api-docs/**", "/api-docs/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(trustedGatewayAuthenticationFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
