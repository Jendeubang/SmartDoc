package com.javaee.gateway.config;

import com.javaee.common.config.CorsOriginPolicy;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 全局跨域配置
 */
@Configuration
public class GlobalCorsConfig {

    @Value("${security.cors.allowed-origins:${CORS_ALLOWED_ORIGINS:}}")
    private String allowedOrigins;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        corsConfig.setAllowedOrigins(CorsOriginPolicy.parse(allowedOrigins, isProduction()));
        corsConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        corsConfig.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-Organization-Id", "X-Requested-With"));
        corsConfig.setExposedHeaders(List.of("Content-Disposition"));
        corsConfig.setAllowCredentials(false);
        corsConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        return new CorsWebFilter(source);
    }

    private boolean isProduction() {
        return Arrays.stream(activeProfiles == null ? new String[]{""} : activeProfiles.split(","))
                .map(String::trim)
                .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                        || profile.equalsIgnoreCase("production"));
    }

}
