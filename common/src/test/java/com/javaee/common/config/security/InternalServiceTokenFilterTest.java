package com.javaee.common.config.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class InternalServiceTokenFilterTest {
    private static final String EXPECTED = "service-secret-for-regression-tests";
    private InternalServiceTokenFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalServiceTokenFilter();
        ReflectionTestUtils.setField(filter, "expected", EXPECTED);
        ReflectionTestUtils.setField(filter, "expectedFile", "");
    }

    @Test
    void internalEndpointRequiresServiceToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/internal/users/7", null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void forgedOrWrongServiceTokenIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/internal/users/7", "client-forged-token"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void correctServiceTokenAllowsInternalEndpoint() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/internal/users/7", EXPECTED), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void publicEndpointDoesNotAccidentallyRequireInternalToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/users/profile", null), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (token != null) request.addHeader("X-Internal-Service-Token", token);
        return request;
    }
}
