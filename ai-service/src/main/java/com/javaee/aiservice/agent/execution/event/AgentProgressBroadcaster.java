package com.javaee.aiservice.agent.execution.event;

/**
 * 【简历：Agent 实时进度 WebSocket 推送】
 * 通过 WebSocket 向客户端推送 Agent 执行时间线和实时进度。
 */

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent 实时进度广播器。若 WebSocket 消息模板不可用，则静默降级，不影响主流程。
 */
// 类职责：Agent 进度广播器（对应简历第1条「WebSocket 实现实时进度推送」）——把执行事件按用户/任务/知识库作业三个维度推到对应 WebSocket 主题；模板不可用时静默降级。
@Component
public class AgentProgressBroadcaster {

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    // 推送执行事件：按 userId / traceId / jobId 三个维度分别广播到不同订阅主题，广播失败不影响任务执行。
    public void publish(AgentProgressEvent event) {
        if (messagingTemplate == null || event == null) {
            return;
        }
        try {
            if (event.getUserId() != null && !event.getUserId().isBlank()) {
                messagingTemplate.convertAndSend("/topic/agent/users/" + event.getUserId(), event);
            }
            if (event.getTraceId() != null && !event.getTraceId().isBlank()) {
                messagingTemplate.convertAndSend("/topic/agent/tasks/" + event.getTraceId(), event);
            }
            if (event.getJobId() != null && !event.getJobId().isBlank()) {
                messagingTemplate.convertAndSend("/topic/agent/knowledge/" + event.getJobId(), event);
            }
        } catch (Exception ignored) {
            // 广播失败不应影响任务执行。
        }
    }
}
