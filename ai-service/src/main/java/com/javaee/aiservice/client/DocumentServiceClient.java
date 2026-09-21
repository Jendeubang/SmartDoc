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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin internal client for document-service.
 */
// document-service 内部客户端（简历第 2 条「权限感知检索」的数据来源）：透传鉴权头并转发 REST 调用
@Component
public class DocumentServiceClient {

    private static final long ACCESS_IDS_CACHE_TTL_MILLIS = Duration.ofSeconds(5).toMillis();
    private final ConcurrentHashMap<String, AccessIdsCacheEntry> accessIdsCache = new ConcurrentHashMap<>();

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${document.service.url:http://localhost:8084}")
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
    // 拉取当前用户可访问的文档 ID 列表，作为权限过滤的数据源
    public List<String> getAccessibleDocumentIds() {
        String cacheKey = accessScopeCacheKey();
        AccessIdsCacheEntry cached = accessIdsCache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
            return cached.documentIds();
        }
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                baseUrl() + "/api/documents/access/ids", HttpMethod.GET,
                new HttpEntity<>(headers()), new ParameterizedTypeReference<>() {});
        Map<String, Object> body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || body == null || !"200".equals(String.valueOf(body.get("code")))) {
            throw new SecurityException("无法获取文档授权范围");
        }
        Object data = body.get("data");
        if (!(data instanceof List<?> list)) {
            accessIdsCache.put(cacheKey, new AccessIdsCacheEntry(List.of(), System.currentTimeMillis() + ACCESS_IDS_CACHE_TTL_MILLIS));
            return List.of();
        }
        List<String> documentIds = list.stream().map(String::valueOf).toList();
        accessIdsCache.put(cacheKey, new AccessIdsCacheEntry(
                documentIds, System.currentTimeMillis() + ACCESS_IDS_CACHE_TTL_MILLIS));
        return documentIds;
    }

    /**
     * 权限变化后由协作/分享写接口调用，避免短 TTL 内继续使用旧权限范围。
     */
    public void evictAccessibleDocumentIdsCache() {
        accessIdsCache.remove(accessScopeCacheKey());
    }

    private String accessScopeCacheKey() {
        return String.join("|",
                requestHeader("X-User-Id"),
                requestHeader("X-Organization-Id"),
                requestHeader("X-Role"),
                Integer.toHexString(Objects.hashCode(currentAuthorization())));
    }

    private String requestHeader(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String value = attributes.getRequest().getHeader(name);
            return value == null ? "" : value;
        }
        return "";
    }

    private record AccessIdsCacheEntry(List<String> documentIds, long expiresAtMillis) {}

    @SuppressWarnings("unchecked")
    // 拉取当前用户可访问的文档目录列表
    public List<Map<String, Object>> getAccessibleDocuments() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                baseUrl() + "/api/documents/access/catalog", HttpMethod.GET,
                new HttpEntity<>(headers()), new ParameterizedTypeReference<>() {});
        Map<String, Object> body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || body == null || !"200".equals(String.valueOf(body.get("code")))) {
            throw new SecurityException("无法获取文档授权目录");
        }
        Object data = body.get("data");
        if (!(data instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }

    // 校验对某文档的读/写权限，无权限时由服务端返回错误
    public void assertDocumentAccess(String documentId, boolean write) {
        restTemplate.exchange(documentUrl(documentId) + "/access?mode=" + (write ? "write" : "read"),
                HttpMethod.GET, new HttpEntity<>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    // 统一解析响应：校验 HTTP 状态与业务 code，取出 data 字段
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

    // 构造请求头：透传经当前服务验证过的用户上下文。
    // 浏览器请求通常携带 Authorization；服务间请求则由内部服务令牌再次校验。
    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String authorization = currentAuthorization();
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        copyTrustedHeader(headers, "X-Internal-Service-Token");
        copyTrustedHeader(headers, "X-User-Id");
        copyTrustedHeader(headers, "X-Role");
        copyTrustedHeader(headers, "X-Organization-Id");
        return headers;
    }

    /**
     * 透传已由当前服务鉴权链校验的内部身份头；下游服务仍会独立校验内部令牌，
     * 因此客户端伪造的头部不会被直接信任。
     */
    private void copyTrustedHeader(HttpHeaders headers, String headerName) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String value = attributes.getRequest().getHeader(headerName);
            if (value != null && !value.isBlank()) {
                headers.set(headerName, value);
            }
        }
    }

    // 从当前请求上下文取出 Authorization 头
    private String currentAuthorization() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }

    // 拼接并 URL 编码单个文档的访问地址
    private String documentUrl(String documentId) {
        String encodedDocumentId = UriUtils.encodePathSegment(documentId, StandardCharsets.UTF_8);
        return baseUrl() + "/api/documents/" + encodedDocumentId;
    }

    private String baseUrl() {
        return documentServiceUrl == null ? "" : documentServiceUrl.replaceAll("/+$", "");
    }
}
