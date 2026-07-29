package com.javaee.documentservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.entity.DocumentAccess;
import com.javaee.documentservice.mapper.DocumentAccessMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档与用户的协作访问控制。
 */
@Service
public class DocumentAccessService {

    private static final Set<String> READ_ROLES = Set.of("owner", "editor", "viewer");
    private static final Set<String> WRITE_ROLES = Set.of("owner", "editor");

    @Autowired
    private DocumentAccessMapper documentAccessMapper;

    @Value("${user.service.url:http://localhost:8081}")
    private String userServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 校验用户是否存在于 user-service
     */
    private boolean userExists(Long userId) {
        try {
            String url = userServiceUrl + "/api/users/" + userId;
            org.springframework.http.ResponseEntity<java.util.Map> response = restTemplate.getForEntity(url, java.util.Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object code = response.getBody().get("code");
                // user-service 返回 code=200 表示成功，code=606 表示用户不存在
                return code != null && (code instanceof Number ? ((Number) code).intValue() == 200 : "200".equals(code.toString()));
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Map<String, Object>> getCollaborators(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return Collections.emptyList();
        }
        List<DocumentAccess> list = documentAccessMapper.selectList(new QueryWrapper<DocumentAccess>()
                .eq("document_id", documentId));
        return list.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", a.getUserId());
            m.put("role", a.getRole());
            m.put("createTime", a.getCreateTime());
            return m;
        }).collect(Collectors.toList());
    }

    public void revokeAccess(String documentId, Long userId) {
        if (documentId == null || documentId.isBlank()) {
            throw new BusinessException("文档ID不能为空");
        }
        if (userId == null) {
            throw new BusinessException("用户ID不能为空");
        }
        documentAccessMapper.delete(new QueryWrapper<DocumentAccess>()
                .eq("document_id", documentId)
                .eq("user_id", userId));
    }

    public void grantOwnerAccess(String documentId, String bucketName, Long userId) {
        grantAccess(documentId, bucketName, userId, "owner");
    }

    public void grantAccess(String documentId, String bucketName, Long userId, String role) {
        if (documentId == null || documentId.isBlank()) {
            throw new BusinessException("文档ID不能为空");
        }
        if (bucketName == null || bucketName.isBlank()) {
            throw new BusinessException("存储桶不能为空");
        }
        if (userId == null) {
            throw new BusinessException("用户ID不能为空");
        }
        // 校验被授权用户是否存在
        if (!userExists(userId)) {
            throw new BusinessException("用户不存在，请检查用户ID是否正确");
        }
        String normalizedRole = normalizeRole(role);
        DocumentAccess existing = documentAccessMapper.selectOne(new QueryWrapper<DocumentAccess>()
                .eq("document_id", documentId)
                .eq("user_id", userId));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            DocumentAccess access = new DocumentAccess();
            access.setDocumentId(documentId);
            access.setBucketName(bucketName);
            access.setUserId(userId);
            access.setRole(normalizedRole);
            access.setCreateTime(now);
            access.setUpdateTime(now);
            documentAccessMapper.insert(access);
            return;
        }
        existing.setBucketName(bucketName);
        existing.setRole(normalizedRole);
        existing.setUpdateTime(now);
        documentAccessMapper.updateById(existing);
    }

    public void assertCanRead(Document document, Long userId) {
        if (!canAccess(document, userId, READ_ROLES)) {
            throw new BusinessException("无权访问此文档");
        }
    }

    public void assertCanWrite(Document document, Long userId) {
        if (!canAccess(document, userId, WRITE_ROLES)) {
            throw new BusinessException("无权修改此文档");
        }
    }

    public boolean isOwner(Document document, Long userId) {
        return document != null && userId != null
                && document.getUserId() != null && document.getUserId().equals(userId);
    }

    private boolean canAccess(Document document, Long userId, Set<String> allowedRoles) {
        if (document == null || userId == null) {
            return false;
        }
        if (isOwner(document, userId)) {
            return true;
        }
        Long count = documentAccessMapper.selectCount(new QueryWrapper<DocumentAccess>()
                .eq("document_id", document.getId())
                .eq("user_id", userId)
                .in("role", allowedRoles));
        return count != null && count > 0;
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "editor" : role.trim().toLowerCase(Locale.ROOT);
        if (!READ_ROLES.contains(normalized)) {
            throw new BusinessException("不支持的协作角色: " + role);
        }
        return normalized;
    }
}
