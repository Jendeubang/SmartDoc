package com.javaee.aiservice.vo;

/** 不包含明文 API Key 的用户模型配置视图。 */
public record UserAIProviderCredentialVO(
        String id,
        String provider,
        String model,
        String baseUrl,
        String keyHint,
        boolean defaultCredential,
        String status,
        String updatedAt
) {
}
