package com.javaee.common.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// 【简历第6条 · 内部服务令牌】服务间令牌提供者：从配置项或密钥文件读取令牌，
// 供各服务调用内部接口时注入 X-Internal-Service-Token 头使用。
@Component
public class InternalServiceTokenProvider {
    @Value("${security.internal-service-token:}") private String value;
    @Value("${security.internal-service-token-file:}") private String file;

    // 读取并返回配置的服务间令牌；优先取配置项，其次取密钥文件，均无则抛异常
    public String getRequiredToken() {
        if (value != null && !value.isBlank()) return value.trim();
        if (file != null && !file.isBlank()) {
            try {
                String token = Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8).trim();
                if (!token.isBlank()) return token;
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to read internal service token secret", exception);
            }
        }
        throw new IllegalStateException("Internal service token is not configured");
    }
}
