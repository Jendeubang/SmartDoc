package com.javaee.aiservice.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.RedisVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import redis.clients.jedis.JedisPooled;

/**
 * AI配置类
 * 手动配置 Spring AI 组件（自动配置无法覆盖的多模型场景）
 *
 * Chat 与 Embedding 都复用 Spring AI 的 OpenAI 兼容客户端，但分别连接不同服务：
 * DeepSeek 负责 Chat，DashScope text-embedding-v4 负责 Embedding。
 * 两者的 endpoint、API Key 与模型名在 application.yml 中独立配置。
 */
@Configuration
public class AiConfig {

    /**
     * DeepSeek 只承担对话生成；向量化始终显式走 DashScope OpenAI 兼容端点。
     * @Primary 使 Spring AI VectorStore 与其他向量化调用统一使用该模型，避免误调 DeepSeek。
     */
    @Bean("dashScopeEmbeddingModel")
    @Primary
    public EmbeddingModel dashScopeEmbeddingModel(
            @Value("${spring.ai.openai.embedding.base-url}") String baseUrl,
            @Value("${spring.ai.openai.embedding.api-key}") String apiKey,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-v4}") String model,
            @Value("${spring.ai.openai.embedding.options.dimensions:1536}") Integer dimensions) {
        OpenAiApi api = new OpenAiApi(baseUrl, apiKey);
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .withModel(model)
                .withDimensions(dimensions)
                .withEncodingFormat("float")
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    @Bean(destroyMethod = "close")
    public JedisPooled vectorStoreJedis(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return new JedisPooled(host, port);
    }

    /**
     * Redis Stack 的 RediSearch 向量索引。元数据字段均建立 TAG 索引，
     * 以便 RAG 在检索前按用户、知识库和企业维度过滤。
     */
    @Bean
    public org.springframework.ai.vectorstore.VectorStore springAiVectorStore(
            @Qualifier("dashScopeEmbeddingModel") EmbeddingModel embeddingModel,
            JedisPooled vectorStoreJedis,
            @Value("${spring.ai.vectorstore.redis.index:doc-ai-vectors}") String indexName,
            @Value("${spring.ai.vectorstore.redis.prefix:vector:}") String prefix) {
        RedisVectorStore.RedisVectorStoreConfig config = RedisVectorStore.RedisVectorStoreConfig.builder()
                .withIndexName(indexName)
                .withPrefix(prefix)
                .withVectorAlgorithm(RedisVectorStore.Algorithm.HSNW)
                .withMetadataFields(
                        RedisVectorStore.MetadataField.tag("id"),
                        RedisVectorStore.MetadataField.tag("documentId"),
                        RedisVectorStore.MetadataField.tag("userId"),
                        RedisVectorStore.MetadataField.tag("knowledgeBaseId"),
                        RedisVectorStore.MetadataField.tag("organizationId"))
                .build();
        return new RedisVectorStore(config, embeddingModel, vectorStoreJedis, true);
    }
}
