package com.javaee.documentservice.client;

import com.javaee.common.config.security.InternalServiceTokenProvider;
import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.vo.DocumentVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ToolboxFileClient {

    private final RestTemplate http;
    private final InternalServiceTokenProvider tokens;
    private final String fileUrl;

    @Autowired
    public ToolboxFileClient(InternalServiceTokenProvider tokens,
                             @Value("${file.service.url:http://localhost:8082}") String fileUrl) {
        this(new RestTemplate(), tokens, fileUrl);
    }

    ToolboxFileClient(RestTemplate http, InternalServiceTokenProvider tokens, String fileUrl) {
        this.http = http;
        this.tokens = tokens;
        this.fileUrl = fileUrl;
    }

    public byte[] download(DocumentVO document) {
        if (document.getFileId() == null || document.getFileId().isBlank()) {
            throw new BusinessException("This document has no source file");
        }
        if (document.getUserId() == null) {
            throw new BusinessException("This document has no owner identity");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Service-Token", tokens.getRequiredToken());
        headers.set("X-User-Id", String.valueOf(document.getUserId()));
        if (document.getOrganizationId() != null && !document.getOrganizationId().isBlank()) {
            headers.set("X-Organization-Id", document.getOrganizationId());
        }

        var uri = UriComponentsBuilder.fromHttpUrl(fileUrl)
                .pathSegment("api", "files", "download", document.getFileId())
                .build()
                .encode()
                .toUri();
        var response = http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        if (response.getBody() == null) {
            throw new BusinessException("File service returned an empty source file");
        }
        return response.getBody();
    }
}
