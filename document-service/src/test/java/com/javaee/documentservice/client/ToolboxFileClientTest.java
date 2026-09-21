package com.javaee.documentservice.client;

import com.javaee.common.config.security.InternalServiceTokenProvider;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ToolboxFileClientTest {

    @Test
    void downloadUsesInternalIdentityWithoutAuthorizationHeader() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        InternalServiceTokenProvider tokens = mock(InternalServiceTokenProvider.class);
        when(tokens.getRequiredToken()).thenReturn("service-secret");
        ToolboxFileClient client = new ToolboxFileClient(http, tokens, "http://file-service:8082");

        DocumentVO document = new DocumentVO();
        document.setFileId("file-1");
        document.setUserId(7L);
        document.setOrganizationId("org-1");

        server.expect(requestTo("http://file-service:8082/api/files/download/file-1"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(header("X-Internal-Service-Token", "service-secret"))
                .andExpect(header("X-User-Id", "7"))
                .andExpect(header("X-Organization-Id", "org-1"))
                .andRespond(withSuccess("source", MediaType.APPLICATION_OCTET_STREAM));

        assertThat(client.download(document)).isEqualTo("source".getBytes(StandardCharsets.UTF_8));
        server.verify();
    }
}
