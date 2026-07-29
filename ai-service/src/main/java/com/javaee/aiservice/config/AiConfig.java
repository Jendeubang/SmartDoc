package com.javaee.aiservice.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Configuration;

/**
 * AI配置类
 * 手动配置 Spring AI 组件（自动配置无法覆盖的多模型场景）
 *
 * 当前使用 DeepSeek API，Embedding 和 Chat 均通过 spring.ai.openai.* 自动配置。
 * DashScope embedding bean 已禁用，如需启用请取消注释。
 */
@Configuration
public class AiConfig {

    //  DashScope Embedding 已禁用 —— 改用 DeepSeek OpenAI 兼容接口
    //  @Bean
    //  @ConditionalOnProperty("spring.ai.dashscope.api-key")
    //  public EmbeddingModel dashScopeEmbeddingModel(
    //          @Value("${spring.ai.dashscope.api-key}") String apiKey,
    //          @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v4}") String modelName) {
    //      OpenAiApi api = new OpenAiApi("https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey);
    //      OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
    //              .withModel(modelName)
    //              .build();
    //      return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    //  }
}