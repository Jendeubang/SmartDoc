package com.javaee.aiservice.factory;

import com.javaee.aiservice.agent.AIService;
import com.javaee.aiservice.agent.OpenAIAIService;
import com.javaee.aiservice.config.MultiModelConfig;
import com.javaee.aiservice.model.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 服务工厂
 * 管理多模型实例（DeepSeek Chat / GLM-5 / Kimi K2.5 / MiniMax M2.5）
 * 所有模型均通过 Spring AI OpenAI 兼容接口调用
 */
@Component
public class AIServiceFactory {

    private static final Logger log = LoggerFactory.getLogger(AIServiceFactory.class);

    /** 默认模型（DeepSeek Chat），由 spring.ai.openai.* 自动配置 */
    @Autowired
    private ChatModel defaultChatModel;

    @Value("${spring.ai.openai.api-key:#{null}}")
    private String defaultApiKey;

    @Value("${spring.ai.openai.base-url:#{null}}")
    private String defaultBaseUrl;

    @Value("${ai.deepseek.read-timeout-ms:120000}")
    private int deepseekReadTimeoutMs;

    @Autowired
    private MultiModelConfig multiModelConfig;

    private final Map<ModelType, AIService> serviceMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("初始化AI服务工厂...");

        for (ModelType modelType : ModelType.values()) {
            String modelCode = modelType.getCode();
            boolean enabled = true;

            // 检查是否有独立配置（ai.models.{code}）
            MultiModelConfig.ModelConfig config = multiModelConfig.getModelConfig(modelCode);

            if (QWEN_TURBO_CODE.equals(modelCode)) {
                ChatModel deepSeekModel = createChatModel(defaultBaseUrl, defaultApiKey, modelCode, deepseekReadTimeoutMs);
                OpenAIAIService service = new OpenAIAIService(deepSeekModel, modelType, enabled);
                serviceMap.put(modelType, service);
                log.info("注册默认模型: {} (readTimeout={}ms)", modelType.getName(), deepseekReadTimeoutMs);
            } else {
                // 其他模型：从 ai.models.{code} 读取独立配置，编程式创建 ChatModel
                if (config != null && !config.isEnabled()) {
                    log.info("模型 {} 已禁用，跳过注册", modelType.getName());
                    OpenAIAIService service = new OpenAIAIService(null, modelType, false);
                    serviceMap.put(modelType, service);
                    continue;
                }

                String apiKey = config != null && config.getApiKey() != null
                        ? config.getApiKey() : defaultApiKey;
                String baseUrl = config != null && config.getBaseUrl() != null
                        ? config.getBaseUrl() : defaultBaseUrl;
                String modelName = config != null && config.getModel() != null
                        ? config.getModel() : modelCode;
                enabled = config == null || config.isEnabled();

                if (apiKey == null || baseUrl == null) {
                    log.warn("模型 {} 缺少 apiKey 或 baseUrl 配置，跳过注册", modelType.getName());
                    continue;
                }

                try {
                    OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);
                    OpenAiChatOptions options = OpenAiChatOptions.builder()
                            .withModel(modelName)
                            .build();
                    ChatModel chatModel = new OpenAiChatModel(openAiApi, options);

                    OpenAIAIService service = new OpenAIAIService(chatModel, modelType, enabled);
                    serviceMap.put(modelType, service);
                    log.info("注册模型: {} (model={}, baseUrl={}, enabled={})",
                            modelType.getName(), modelName, baseUrl, enabled);
                } catch (Exception e) {
                    log.error("创建模型 {} 失败", modelType.getName(), e);
                }
            }
        }

        log.info("AI服务工厂初始化完成，共注册 {} 个模型", serviceMap.size());
    }

    public AIService getService(ModelType modelType) {
        AIService service = serviceMap.get(modelType);
        if (service == null) {
            throw new IllegalArgumentException("不支持的模型类型: " + modelType);
        }
        if (!service.isAvailable()) {
            throw new IllegalStateException("模型 " + modelType.getName() + " 已禁用或不可用");
        }
        return service;
    }

    public AIService getService(String modelCode) {
        return getService(ModelType.fromCode(modelCode));
    }

    public AIService getDefaultService() {
        return getService(ModelType.QWEN_TURBO);
    }

    public Map<ModelType, AIService> getAllServices() {
        return new HashMap<>(serviceMap);
    }

    private ChatModel createChatModel(String baseUrl, String apiKey, String modelName, int readTimeoutMs) {
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("DeepSeek 缺少 apiKey 或 baseUrl 配置");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(15));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(readTimeoutMs, 15_000)));
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey,
                RestClient.builder().requestFactory(requestFactory), WebClient.builder());
        OpenAiChatOptions options = OpenAiChatOptions.builder().withModel(modelName).build();
        return new OpenAiChatModel(openAiApi, options);
    }

    private static final String QWEN_TURBO_CODE = "deepseek-chat";
}
