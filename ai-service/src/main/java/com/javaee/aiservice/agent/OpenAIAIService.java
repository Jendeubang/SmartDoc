package com.javaee.aiservice.agent;

import com.javaee.aiservice.model.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * 基于 Spring AI ChatModel 的 OpenAI 兼容接口实现
 * 支持所有 OpenAI 兼容的模型（DeepSeek / GLM / Kimi / MiniMax 等）
 */
public class OpenAIAIService implements AIService {

    private static final Logger log = LoggerFactory.getLogger(OpenAIAIService.class);

    private final ChatModel chatModel;
    private final ModelType modelType;
    private final boolean enabled;

    private static final String SYSTEM_PROMPT = "你是一个专业的AI文档助手，擅长文本处理。你的任务是根据用户指令对文本进行总结、润色、纠错、改写、分析或提取关键词。请直接执行任务并返回纯文本结果，不要使用Markdown格式（不要用*、#、-等符号），不要自我介绍或问候。";

    public OpenAIAIService(ChatModel chatModel, ModelType modelType, boolean enabled) {
        this.chatModel = chatModel;
        this.modelType = modelType;
        this.enabled = enabled;
    }

    @Override
    public String callChat(String prompt) {
        if (!enabled) {
            throw new RuntimeException("Model " + modelType.getName() + " is disabled");
        }
        if (chatModel == null) {
            throw new RuntimeException("ChatModel 未初始化");
        }

        log.info("调用Spring AI ChatModel: {}, prompt长度: {}", modelType.getName(), prompt.length());

        try {
            SystemMessage system = new SystemMessage(SYSTEM_PROMPT);
            UserMessage user = new UserMessage(prompt);
            Prompt springPrompt = new Prompt(List.of(system, user));

            ChatResponse response = chatModel.call(springPrompt);

            String result = response.getResult().getOutput().getContent();
            log.info("{}响应成功", modelType.getName());
            return result;
        } catch (Exception e) {
            log.error("调用{}失败", modelType.getName(), e);
            throw new RuntimeException("调用" + modelType.getName() + "失败: " + e.getMessage(), e);
        }
    }

    @Override
    public ModelType getModelType() {
        return modelType;
    }

    @Override
    public boolean isAvailable() {
        return enabled && chatModel != null;
    }
}
