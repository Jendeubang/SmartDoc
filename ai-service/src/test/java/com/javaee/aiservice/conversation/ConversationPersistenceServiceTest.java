package com.javaee.aiservice.conversation;

import com.javaee.common.config.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationPersistenceServiceTest {
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void enterpriseConversationLookupAlwaysIncludesTenant() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantContext.set("org-a");
        new ConversationPersistenceService(jdbc).isOwner("conv-1", "7");
        verify(jdbc).queryForObject(contains("organization_id=?"), eq(Integer.class),
                eq("conv-1"), eq("7"), eq("org-a"));
    }

    @Test void personalConversationLookupExcludesEnterpriseRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ConversationPersistenceService(jdbc).isOwner("conv-1", "7");
        verify(jdbc).queryForObject(contains("organization_id IS NULL"), eq(Integer.class),
                eq("conv-1"), eq("7"));
    }
}
