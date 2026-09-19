package com.javaee.user.config;

import com.javaee.common.config.security.BaseSecurityConfig;
import com.javaee.common.config.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends BaseSecurityConfig {
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) { super(jwtAuthenticationFilter); }

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        applyBaseSecurityConfig(http);
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/internal/**").permitAll()
                .requestMatchers("/api/users/login", "/api/users/register", "/api/users/refresh",
                        "/api/users/password/forgot", "/api/users/password/reset").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/v3/api-docs/**", "/v3/api-docs").hasRole("ADMIN")
                .requestMatchers("/static/**", "/public/**", "/error").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
