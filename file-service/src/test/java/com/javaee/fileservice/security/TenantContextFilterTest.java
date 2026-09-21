package com.javaee.fileservice.security;

import com.javaee.common.config.security.TenantContext;
import com.javaee.common.config.security.SessionTokenValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantContextFilterTest {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); }

    @Test void rejectsOrganizationWhenUserIsNotAnActiveMember() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM enterprise_member WHERE organization_id=? AND user_id=? AND status='active'"),
                eq(Integer.class), eq("org-b"), eq(7L))).thenReturn(0);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("7", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest(); request.addHeader("X-Organization-Id", "org-b");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TenantContextFilter(jdbc, mock(SessionTokenValidator.class)).doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(TenantContext.get()).isNull();
    }

    @Test void exposesValidatedOrganizationOnlyDuringRequest() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM enterprise_member WHERE organization_id=? AND user_id=? AND status='active'"),
                eq(Integer.class), eq("org-a"), eq(7L))).thenReturn(1);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("7", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest(); request.addHeader("X-Organization-Id", "org-a");
        MockHttpServletResponse response = new MockHttpServletResponse(); final String[] observed = new String[1];
        new TenantContextFilter(jdbc, mock(SessionTokenValidator.class)).doFilter(request, response, (req, res) -> observed[0] = TenantContext.get());
        assertThat(observed[0]).isEqualTo("org-a");
        assertThat(TenantContext.get()).isNull();
    }
}
