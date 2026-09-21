package com.javaee.documentservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.javaee.common.exception.BusinessException;
import com.javaee.common.config.security.InternalServiceTokenProvider;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.entity.DocumentAccess;
import com.javaee.documentservice.mapper.DocumentAccessMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档与用户的协作访问控制。
 */
// 对应简历第 6 条「企业空间与在线协同」：grantAccess 为文档授予 owner/editor/viewer 协作角色（支持过期时间）。
@Service
public class DocumentAccessService {

    private static final Set<String> READ_ROLES = Set.of("owner", "editor", "viewer");
    private static final Set<String> WRITE_ROLES = Set.of("owner", "editor");

    @Autowired
    private DocumentAccessMapper documentAccessMapper;

    @Autowired
    private EnterprisePermissionService enterprisePermissionService;

    @Value("${user.service.url:http://localhost:8081}")
    private String userServiceUrl;
    @Autowired private InternalServiceTokenProvider internalServiceTokenProvider;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 校验用户是否存在于 user-service
     */
    private boolean userExists(Long userId) {
        try {
            String url = userServiceUrl + "/api/internal/users/" + userId;
            HttpHeaders headers = new HttpHeaders(); headers.set("X-Internal-Service-Token", internalServiceTokenProvider.getRequiredToken());
            org.springframework.http.ResponseEntity<java.util.Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), java.util.Map.class);
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

    // 查询文档的全部协作者及其角色、过期状态
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
            m.put("expiresAt", a.getExpiresAt());
            m.put("expired", a.getExpiresAt() != null && a.getExpiresAt().isBefore(LocalDateTime.now()));
            m.put("createTime", a.getCreateTime());
            return m;
        }).collect(Collectors.toList());
    }

    // 撤销某用户对文档的协作授权
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
        grantAccess(documentId, bucketName, userId, "owner", 0);
    }

    public void grantAccess(String documentId, String bucketName, Long userId, String role) {
        grantAccess(documentId, bucketName, userId, role, 0);
    }

    // 授予协作者 owner/editor/viewer 角色，可按小时设置过期时间；已存在则更新授权
    public void grantAccess(String documentId, String bucketName, Long userId, String role, Integer expiresHours) {
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
        int normalizedExpiry = expiresHours == null ? 0 : expiresHours;
        if (normalizedExpiry < 0 || normalizedExpiry > 8760) {
            throw new BusinessException("协作有效期必须在 0 到 8760 小时之间");
        }
        LocalDateTime expiresAt = normalizedExpiry == 0 ? null : LocalDateTime.now().plusHours(normalizedExpiry);
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
            access.setExpiresAt(expiresAt);
            access.setCreateTime(now);
            access.setUpdateTime(now);
            documentAccessMapper.insert(access);
            return;
        }
        existing.setBucketName(bucketName);
        existing.setRole(normalizedRole);
        existing.setExpiresAt(expiresAt);
        existing.setUpdateTime(now);
        documentAccessMapper.updateById(existing);
    }

    // 断言当前用户可读文档，否则抛出无权异常
    public void assertCanRead(Document document, Long userId) {
        if (!canAccess(document, userId, READ_ROLES)) {
            throw new BusinessException("无权访问此文档");
        }
    }

    // 断言当前用户可写文档，否则抛出无权异常
    public void assertCanWrite(Document document, Long userId) {
        if (!canAccess(document, userId, WRITE_ROLES)) {
            throw new BusinessException("无权修改此文档");
        }
    }

    // 断言当前用户可评论/建议：所有者、企业成员或有效协作角色均可
    public void assertCanComment(Document document, Long userId) {
        if (isOwner(document, userId)) return;
        if (document != null && document.getOrganizationId() != null
                && enterprisePermissionService.canComment(document, userId)) return;
        Long count = documentAccessMapper.selectCount(new QueryWrapper<DocumentAccess>()
                .eq("document_id", document.getId())
                .eq("user_id", userId)
                .in("role", READ_ROLES)
                .and(w -> w.isNull("expires_at").or().gt("expires_at", LocalDateTime.now())));
        if (count == null || count == 0) throw new BusinessException("当前文档权限不允许评论或建议");
    }

    // 判断用户是否为文档所有者
    public boolean isOwner(Document document, Long userId) {
        return document != null && userId != null
                && document.getUserId() != null && document.getUserId().equals(userId);
    }

    // 核心权限判断：所有者直接放行，其次走企业权限，再查未过期的协作授权
    private boolean canAccess(Document document, Long userId, Set<String> allowedRoles) {
        if (document == null || userId == null) {
            return false;
        }
        if (isOwner(document, userId)) {
            return true;
        }
        if (document.getOrganizationId() != null && !document.getOrganizationId().isBlank()) {
            if (allowedRoles.equals(READ_ROLES) && enterprisePermissionService.canRead(document, userId)) return true;
            if (allowedRoles.equals(WRITE_ROLES) && enterprisePermissionService.canWrite(document, userId)) return true;
        }
        Long count = documentAccessMapper.selectCount(new QueryWrapper<DocumentAccess>()
                .eq("document_id", document.getId())
                .eq("user_id", userId)
                .in("role", allowedRoles)
                .and(w -> w.isNull("expires_at").or().gt("expires_at", LocalDateTime.now())));
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
