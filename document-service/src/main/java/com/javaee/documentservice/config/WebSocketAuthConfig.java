package com.javaee.documentservice.config;

import com.javaee.common.utils.JwtUtils;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.service.DocumentAccessService;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.List;

@Configuration
public class WebSocketAuthConfig implements WebSocketMessageBrokerConfigurer {
    private static final String DOCUMENT_TOPIC = "/topic/doc/";

    private final DocumentMapper documentMapper;
    private final DocumentAccessService documentAccessService;

    public WebSocketAuthConfig(DocumentMapper documentMapper, DocumentAccessService documentAccessService) {
        this.documentMapper = documentMapper;
        this.documentAccessService = documentAccessService;
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
        if (!JwtUtils.validateToken(token)) throw new IllegalArgumentException("Invalid WebSocket token");
        Long userId = JwtUtils.getUserId(token);
        String username = JwtUtils.getUsername(token);
        accessor.setUser(new CollaborationPrincipal(String.valueOf(userId), username));
        accessor.getSessionAttributes().put("userId", String.valueOf(userId));
        accessor.getSessionAttributes().put("username", username == null ? String.valueOf(userId) : username);
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(DOCUMENT_TOPIC)) return;
        Principal principal = accessor.getUser();
        if (principal == null) throw new IllegalArgumentException("WebSocket authentication required");
        String documentId = destination.substring(DOCUMENT_TOPIC.length());
        Document document = documentMapper.selectById(documentId);
        if (document == null) throw new IllegalArgumentException("Document does not exist");
        documentAccessService.assertCanRead(document, Long.valueOf(principal.getName()));
    }

    private String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public record CollaborationPrincipal(String name, String username) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
