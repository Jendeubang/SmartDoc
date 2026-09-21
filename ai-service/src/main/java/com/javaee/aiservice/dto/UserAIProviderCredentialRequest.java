package com.javaee.aiservice.dto;

/**
 * 用户自带模型密钥配置。
 * API Key 只在保存/测试时进入服务端，不会在响应中返回。
 */
public record UserAIProviderCredentialRequest(
        String provider,
        String model,
        String baseUrl,
        String apiKey,
        Boolean defaultCredential
) {
}
