package com.javaee.aiservice.rag;

/**
 * 【简历：DashScope Embedding 向量化】
 * 集成 DashScope Embedding API 将文档分段转换为向量，
 * 为语义检索提供向量基础，支持批量向量化。
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档向量化器
 * 使用 Spring AI EmbeddingModel 将文档内容转换为向量表示
 */
@Component
public class DocumentVectorizer {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorizer.class);

    private EmbeddingModel embeddingModel;

    public DocumentVectorizer() {
        // 无参构造函数：当没有 EmbeddingModel bean 时使用
    }

    @Autowired(required = false)
    public DocumentVectorizer(@Qualifier("dashScopeEmbeddingModel") EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 将文本向量化
     * @param text 文本内容
     * @return 向量表示
     */
    public float[] vectorize(String text) {
        log.debug("开始向量化文本，长度={}", text.length());

        try {
            float[] embedding = embeddingModel.embed(text);
            log.debug("向量化完成，维度={}", embedding.length);
            return embedding;
        } catch (Exception e) {
            log.error("调用Embedding API失败", e);
            throw new RuntimeException("文档向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量向量化
     * @param texts 文本列表
     * @return 向量列表
     */
    public float[][] vectorizeBatch(String[] texts) {
        log.info("批量向量化，数量={}", texts.length);

        try {
            List<float[]> embeddings = embeddingModel.embed(List.of(texts));
            float[][] result = new float[embeddings.size()][];

            for (int i = 0; i < embeddings.size(); i++) {
                result[i] = embeddings.get(i);
            }

            log.info("批量向量化完成");
            return result;
        } catch (Exception e) {
            log.error("批量向量化失败", e);
            throw new RuntimeException("批量向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取向量维度
     * @return 向量维度
     */
    public int getVectorDimension() {
        try {
            float[] sample = vectorize("test");
            return sample.length;
        } catch (Exception e) {
            log.warn("获取向量维度失败，使用默认值 1024", e);
            return 1024;
        }
    }
}
