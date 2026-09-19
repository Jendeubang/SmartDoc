package com.javaee.aiservice.async;

/**
 * 【简历：异步 AI 任务消息模型】
 * RabbitMQ 消息体，包含任务类型、文档 ID、参数等。
 */

import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Message sent to RabbitMQ for asynchronous model execution.
 */
@Data
public class AsyncAIJobMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String jobId;
    private String type;
    private String model;
    private String userId;
    private String organizationId;
    private Long createdAt;
    private Map<String, Object> payload = new HashMap<>();
}
