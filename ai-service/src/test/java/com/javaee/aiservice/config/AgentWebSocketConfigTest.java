package com.javaee.aiservice.config;

import com.javaee.aiservice.agent.KnowledgeIndexAgent;
import com.javaee.aiservice.agent.execution.task.AgentTaskRegistry;
import com.javaee.common.config.security.SessionTokenValidator;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWebSocketConfigTest {
    @Test
    void rejectsAnotherUsersTopic() {
        AgentTaskRegistry tasks = mock(AgentTaskRegistry.class);
        KnowledgeIndexAgent jobs = mock(KnowledgeIndexAgent.class);
        AgentWebSocketConfig config = new AgentWebSocketConfig(tasks, jobs, mock(SessionTokenValidator.class), "http://localhost:5173");
        when(tasks.get("task-1")).thenReturn(Map.of("userId", "8"));
        assertThatThrownBy(() -> subscribe(config, "7", "/topic/agent/tasks/task-1"))
                .hasMessageContaining("denied");
    }

    private void subscribe(AgentWebSocketConfig config, String userId, String destination) throws Exception {
        var method = AgentWebSocketConfig.class.getDeclaredMethod("authorizeSubscription", StompHeaderAccessor.class);
        method.setAccessible(true);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(new AgentWebSocketConfig.AgentPrincipal(userId));
        accessor.setDestination(destination);
        try { method.invoke(config, accessor); }
        catch (java.lang.reflect.InvocationTargetException exception) { throw (Exception) exception.getCause(); }
    }
}
