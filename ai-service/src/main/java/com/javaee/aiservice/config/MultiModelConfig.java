package com.javaee.aiservice.config;

/**
 * 【简历：多模型配置】
 * 配置 DashScope、OpenAI 等多模型接入参数，
 * 支持模型类型切换和 API Key 管理。
 */

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 多模型配置
 * 读取 ai.models.* 下每个模型的独立配置（apiKey / baseUrl / model / enabled）
 * 所有模型均为 OpenAI 兼容接口，由 AIServiceFactory 编程式创建 ChatModel bean
 */
@Configuration
@ConfigurationProperties(prefix = "ai")
public class MultiModelConfig {

    private Map<String, ModelConfig> models = new HashMap<>();

    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelConfig> models) {
        this.models = models;
    }

    /**
     * 根据模型代码获取配置
     */
    public ModelConfig getModelConfig(String code) {
        return models.get(code);
    }

    public static class ModelConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private boolean enabled = true;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
