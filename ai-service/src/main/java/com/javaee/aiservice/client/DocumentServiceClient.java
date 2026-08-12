package com.javaee.aiservice.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin internal client for document-service.
 */
@Component
public class DocumentServiceClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${document.service.url:http://document:8084}")
    private String documentServiceUrl;

    public Map<String, Object> getDocument(String documentId) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                documentUrl(documentId),
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<>() {}
        );
        return extractData(response);
    }

    public Map<String, Object> updateDocument(String documentId, Map<String, Object> payload) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                documentUrl(documentId),
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers()),
                new ParameterizedTypeReference<>() {}
        );
        return extractData(response);
    }

    public Map<String, Object> getDocumentStorage(String documentId) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                documentUrl(documentId) + "/storage",
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<>() {}
        );
        return extractData(response);
    }

    public void deleteDocument(String documentId) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                documentUrl(documentId),
                HttpMethod.DELETE,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<>() {}
        );
        extractData(response);
    }

    @SuppressWarnings("unchecked")
    public List<String> getAccessibleDocumentIds() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                baseUrl() + "/api/documents/access/ids", HttpMethod.GET,
                new HttpEntity<>(headers()), new ParameterizedTypeReference<>() {});
        Map<String, Object> body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || body == null || !"200".equals(String.valueOf(body.get("code")))) {
            throw new SecurityException("无法获取文档授权范围");
        }
        Object data = body.get("data");
        if (!(data instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    public void assertDocumentAccess(String documentId, boolean write) {
        restTemplate.exchange(documentUrl(documentId) + "/access?mode=" + (write ? "write" : "read"),
                HttpMethod.GET, new HttpEntity<>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(ResponseEntity<Map<String, Object>> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("document-service returned HTTP " + response.getStatusCode().value());
        }
        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("document-service returned empty response");
        }
        Object code = body.get("code");
        if (code != null && !"200".equals(String.valueOf(code))) {
            Object message = body.getOrDefault("message", "unknown error");
            throw new IllegalStateException("document-service error: " + message);
        }
        Object data = body.get("data");
        if (data instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        if (body.containsKey("data")) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(body);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String authorization = currentAuthorization();
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return headers;
    }

    private String currentAuthorization() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }

    private String documentUrl(String documentId) {
        String encodedDocumentId = UriUtils.encodePathSegment(documentId, StandardCharsets.UTF_8);
        return baseUrl() + "/api/documents/" + encodedDocumentId;
    }

    private String baseUrl() {
        return documentServiceUrl == null ? "" : documentServiceUrl.replaceAll("/+$", "");
    }
}
