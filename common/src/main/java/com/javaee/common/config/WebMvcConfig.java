package com.javaee.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * @description: Web配置（跨域/拦截器）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${security.cors.allowed-origins:${CORS_ALLOWED_ORIGINS:}}")
    private String allowedOrigins;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    /**
     * 配置跨域请求
     * @param registry 跨域注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = CorsOriginPolicy.parse(allowedOrigins, isProduction());
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept", "X-Organization-Id", "X-Requested-With")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(false)
                .maxAge(3600);
    }

    private boolean isProduction() {
        return Arrays.stream(activeProfiles == null ? new String[]{""} : activeProfiles.split(","))
                .map(String::trim)
                .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                        || profile.equalsIgnoreCase("production"));
    }
}
