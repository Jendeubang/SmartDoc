package com.javaee.documentservice.service;

import com.javaee.documentservice.entity.Document;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseTenantIsolationTest {

    @Test
    void memberOfOrganizationBCannotReadOrganizationADocument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EnterprisePermissionService permissions = new EnterprisePermissionService(jdbc);
        when(jdbc.queryForList(eq("SELECT * FROM enterprise_member WHERE organization_id=? AND user_id=? AND status='active'"),
                eq("org-a"), eq(22L))).thenReturn(List.of());

        Document document = new Document();
        document.setId("document-a");
        document.setOrganizationId("org-a");

        assertThat(permissions.canRead(document, 22L)).isFalse();
    }

    @Test
    void sameOrganizationMemberStillCannotCrossDepartmentBoundary() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EnterprisePermissionService permissions = new EnterprisePermissionService(jdbc);
        when(jdbc.queryForList(eq("SELECT * FROM enterprise_member WHERE organization_id=? AND user_id=? AND status='active'"),
                eq("org-a"), eq(7L))).thenReturn(List.of(Map.of(
                "organization_id", "org-a", "user_id", 7L, "role", "member", "department_id", "department-b")));

        Document document = new Document();
        document.setId("document-a");
        document.setOrganizationId("org-a");
        document.setDepartmentId("department-a");

        assertThat(permissions.canRead(document, 7L)).isFalse();
    }
}
