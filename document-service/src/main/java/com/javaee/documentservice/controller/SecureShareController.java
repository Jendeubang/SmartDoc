package com.javaee.documentservice.controller;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.model.Result;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.security.RequestUserContext;
import com.javaee.documentservice.service.DocumentAccessService;
import com.javaee.documentservice.service.DocumentContentService;
import com.javaee.documentservice.service.EnterpriseAuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

// 安全分享链接接口：生成带令牌/密码/过期时间的文档分享链接，并支持匿名访问校验。
// 对应简历第 6 条「企业空间与在线协同」中的安全分享链接。
@RestController
public class SecureShareController {
    private final JdbcTemplate jdbc;
    private final DocumentMapper documents;
    private final DocumentAccessService access;
    private final DocumentContentService contents;
    private final RequestUserContext users;
    private final EnterpriseAuditService audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public SecureShareController(JdbcTemplate jdbc, DocumentMapper documents, DocumentAccessService access,
                                 DocumentContentService contents, RequestUserContext users, EnterpriseAuditService audit) {
        this.jdbc = jdbc; this.documents = documents; this.access = access; this.contents = contents; this.users = users; this.audit = audit;
    }

    // 创建安全分享：生成随机令牌，支持密码、过期时间与访问次数上限
    @PostMapping("/api/enterprise/shares")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId();
        String documentId = text(body.get("documentId"));
        Document document = documents.selectById(documentId);
        if (document == null) throw new BusinessException("文档不存在");
        access.assertCanWrite(document, userId);
        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String password = text(body.get("password"));
        int expiresHours = intValue(body.get("expiresHours"), 0, 8760, 168);
        int maxAccess = intValue(body.get("maxAccess"), 0, 100000, 0);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO document_secure_share(id,token_hash,document_id,organization_id,created_by,password_hash,expires_at,max_access) VALUES(?,?,?,?,?,?,?,?)",
                id, sha256(rawToken), documentId, document.getOrganizationId(), userId, password.isBlank() ? null : encoder.encode(password),
                expiresHours == 0 ? null : LocalDateTime.now().plusHours(expiresHours), maxAccess);
        audit.record(document.getOrganizationId(), userId, "SHARE_CREATE", "share", id, "document=" + documentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("token", rawToken); result.put("expiresHours", expiresHours); result.put("maxAccess", maxAccess);
        return Result.success(result);
    }

    // 列出当前用户创建的分享链接
    @GetMapping("/api/enterprise/shares")
    public Result<List<Map<String, Object>>> list() {
        Long userId = users.getRequiredUserId();
        return Result.success(jdbc.queryForList("SELECT s.id,s.document_id,d.title,s.expires_at,s.max_access,s.access_count,s.status,s.create_time,(s.password_hash IS NOT NULL) password_protected FROM document_secure_share s LEFT JOIN document d ON d.id=s.document_id WHERE s.created_by=? ORDER BY s.create_time DESC", userId));
    }

    // 撤销分享链接
    @DeleteMapping("/api/enterprise/shares/{id}")
    public Result<Void> revoke(@PathVariable String id) {
        Long userId = users.getRequiredUserId();
        jdbc.update("UPDATE document_secure_share SET status='revoked' WHERE id=? AND created_by=?", id, userId);
        audit.record(null, userId, "SHARE_REVOKE", "share", id, "revoked");
        return Result.success();
    }

    // 匿名打开分享：校验状态、过期时间、访问次数与密码后返回文档内容
    @PostMapping("/api/public/shares/{token}")
    public Result<Map<String, Object>> open(@PathVariable String token, @RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM document_secure_share WHERE token_hash=?", sha256(token));
        if (rows.isEmpty()) throw new BusinessException("分享链接不存在");
        Map<String, Object> share = rows.get(0);
        if (!"active".equals(share.get("status"))) throw new BusinessException("分享链接已撤销");
        LocalDateTime expiresAt = (LocalDateTime) share.get("expires_at");
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) throw new BusinessException("分享链接已过期");
        int max = ((Number) share.get("max_access")).intValue(), count = ((Number) share.get("access_count")).intValue();
        if (max > 0 && count >= max) throw new BusinessException("分享链接访问次数已用完");
        String passwordHash = (String) share.get("password_hash");
        if (passwordHash != null && !encoder.matches(text(body == null ? null : body.get("password")), passwordHash)) throw new BusinessException("分享密码错误");
        int updated = jdbc.update("UPDATE document_secure_share SET access_count=access_count+1 WHERE id=? AND status='active' AND (max_access=0 OR access_count<max_access)", share.get("id"));
        if (updated == 0) throw new BusinessException("分享链接不可用");
        Document document = documents.selectById(String.valueOf(share.get("document_id")));
        if (document == null || !"active".equals(document.getStatus())) throw new BusinessException("文档不存在或已删除");
        String content = document.getContent();
        if (document.getObjectName() != null && document.getBucketName() != null) {
            content = contents.getContentByKey(document.getObjectName(), document.getBucketName());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", document.getTitle()); result.put("content", content); result.put("updateTime", document.getUpdateTime());
        audit.record(document.getOrganizationId(), ((Number) share.get("created_by")).longValue(), "SHARE_ACCESS", "document", document.getId(), "anonymous access");
        return Result.success(result);
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private int intValue(Object value, int min, int max, int fallback) { try { int n = Integer.parseInt(text(value)); return n >= min && n <= max ? n : fallback; } catch (Exception e) { return fallback; } }
    // 对令牌做 SHA-256 摘要后存储，避免明文泄露
    private String sha256(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(bytes); } catch (Exception e) { throw new IllegalStateException(e); } }
}
