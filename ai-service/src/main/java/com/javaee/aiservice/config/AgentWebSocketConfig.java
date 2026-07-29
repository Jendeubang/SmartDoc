package com.javaee.aiservice.config;

/**
 * 【简历：Agent WebSocket 端点配置】
 * 配置 Agent 实时进度推送的 WebSocket 端点和拦截器。
 */

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Agent 工作台实时进度通道。
 * 前端可连接 /ws/agent，并订阅 /topic/agent/** 获取任务和索引事件。
 */
@Configuration
@EnableWebSocketMessageBroker
public class AgentWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/agent")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
