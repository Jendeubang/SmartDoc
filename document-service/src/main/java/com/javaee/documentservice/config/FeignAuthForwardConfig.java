package com.javaee.documentservice.config;

import com.javaee.common.config.security.InternalServiceTokenProvider;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Forward user identity to internal service calls.
 */
@Configuration
public class FeignAuthForwardConfig {

    private final InternalServiceTokenProvider internalServiceTokenProvider;

    public FeignAuthForwardConfig(InternalServiceTokenProvider internalServiceTokenProvider) {
        this.internalServiceTokenProvider = internalServiceTokenProvider;
    }

    @Bean
    public RequestInterceptor authForwardInterceptor() {
        return template -> {
            // file-service does not trust a caller-supplied X-User-Id. Internal Feign calls must
            // include the shared service token so it can rebuild an authenticated user context.
            template.header("X-Internal-Service-Token", internalServiceTokenProvider.getRequiredToken());
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return;
            }
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                template.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            String userId = attributes.getRequest().getHeader("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                template.header("X-User-Id", userId);
            }
        };
    }
}
