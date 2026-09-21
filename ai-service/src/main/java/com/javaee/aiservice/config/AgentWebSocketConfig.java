package com.javaee.aiservice.config;

/**
 * 【简历：Agent WebSocket 端点配置】
 * 配置 Agent 实时进度推送的 WebSocket 端点和拦截器。
 */

import com.javaee.aiservice.agent.KnowledgeIndexAgent;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.common.utils.JwtUtils;
import com.javaee.common.config.security.SessionTokenValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Agent 工作台实时进度通道。
 * 前端可连接 /ws/agent，并订阅 /topic/agent/** 获取任务和索引事件。
 */
@Configuration
@EnableWebSocketMessageBroker
public class AgentWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AgentTaskRegistry taskRegistry;
    private final KnowledgeIndexAgent knowledgeIndexAgent;
    private final String[] allowedOrigins;
    private final SessionTokenValidator sessions;

    public AgentWebSocketConfig(AgentTaskRegistry taskRegistry,
                                @Lazy KnowledgeIndexAgent knowledgeIndexAgent,
                                SessionTokenValidator sessions,
                                @Value("${security.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.taskRegistry = taskRegistry;
        this.knowledgeIndexAgent = knowledgeIndexAgent;
        this.sessions = sessions;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toArray(String[]::new);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/agent")
                .setAllowedOriginPatterns(allowedOrigins)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) return message;
                if (StompCommand.CONNECT.equals(accessor.getCommand())) authenticate(accessor);
                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) authorizeSubscription(accessor);
                return message;
            }
        });
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = first(accessor.getNativeHeader("Authorization"));
        if (authorization == null) authorization = first(accessor.getNativeHeader("authorization"));
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("WebSocket authentication required");
        }
        String token = authorization.substring(7);
        String userId;
        try { userId = String.valueOf(sessions.requireActiveAccessToken(token)); }
        catch (SecurityException exception) { throw new IllegalArgumentException("Invalid or revoked WebSocket token"); }
        accessor.setUser(new AgentPrincipal(userId));
        if (accessor.getSessionAttributes() != null) accessor.getSessionAttributes().put("userId", userId);
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) throw new IllegalArgumentException("WebSocket authentication required");
        String destination = accessor.getDestination();
        if (destination == null) return;
        String userId = principal.getName();
        if (destination.equals("/topic/agent/progress")) throw new IllegalArgumentException("Global Agent progress topic is not available");
        String userPrefix = "/topic/agent/users/";
        if (destination.startsWith(userPrefix)) {
            if (!destination.equals(userPrefix + userId)) throw new IllegalArgumentException("Cannot subscribe to another user's Agent events");
            return;
        }
        String taskPrefix = "/topic/agent/tasks/";
        if (destination.startsWith(taskPrefix)) {
            Map<String, Object> task = taskRegistry.get(destination.substring(taskPrefix.length()));
            if (task.isEmpty() || !userId.equals(String.valueOf(task.get("userId")))) {
                throw new IllegalArgumentException("Agent task subscription denied");
            }
            return;
        }
        String knowledgePrefix = "/topic/agent/knowledge/";
        if (destination.startsWith(knowledgePrefix)) {
            Map<String, Object> job = knowledgeIndexAgent.getJobStatus(destination.substring(knowledgePrefix.length()));
            if ("not_found".equals(job.get("status")) || !userId.equals(String.valueOf(job.get("userId")))) {
                throw new IllegalArgumentException("Knowledge job subscription denied");
            }
            return;
        }
        throw new IllegalArgumentException("Agent topic subscription denied");
    }

    private String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public record AgentPrincipal(String name) implements Principal {
        @Override public String getName() { return name; }
    }
}
