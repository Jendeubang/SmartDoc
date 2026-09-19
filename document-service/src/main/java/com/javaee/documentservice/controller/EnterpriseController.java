package com.javaee.documentservice.controller;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.model.Result;
import com.javaee.common.config.security.InternalServiceTokenProvider;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.security.RequestUserContext;
import com.javaee.documentservice.service.DocumentAccessService;
import com.javaee.documentservice.service.EnterpriseAuditService;
import com.javaee.documentservice.service.EnterprisePermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.util.*;

// 企业空间控制器：负责成员管理、角色分配、部门管理、文件夹以及文档移入/移出企业空间等。
// 对应简历第 6 条「企业空间与在线协同」中的成员管理、角色权限、部门管理与文档共享。
@RestController
@RequestMapping("/api/enterprise")
public class EnterpriseController {
    private static final Set<String> MEMBER_ROLES = Set.of("enterprise_admin", "department_admin", "member", "guest");
    private final JdbcTemplate jdbc;
    private final RequestUserContext users;
    private final EnterprisePermissionService permissions;
    private final EnterpriseAuditService audit;
    private final DocumentMapper documentMapper;
    private final DocumentAccessService documentAccess;
    private final RestTemplate rest = new RestTemplate();
    @Value("${user.service.url:http://localhost:8081}") private String userServiceUrl;
    private final InternalServiceTokenProvider internalServiceTokenProvider;

    public EnterpriseController(JdbcTemplate jdbc, RequestUserContext users, EnterprisePermissionService permissions,
                                EnterpriseAuditService audit, DocumentMapper documentMapper, DocumentAccessService documentAccess,
                                InternalServiceTokenProvider internalServiceTokenProvider) {
        this.jdbc = jdbc; this.users = users; this.permissions = permissions; this.audit = audit;
        this.documentMapper = documentMapper; this.documentAccess = documentAccess;
        this.internalServiceTokenProvider = internalServiceTokenProvider;
    }

    // 查询当前用户加入的所有企业及其成员角色、部门信息
    @GetMapping("/organizations")
    public Result<List<Map<String, Object>>> organizations() {
        Long userId = users.getRequiredUserId();
        return Result.success(jdbc.queryForList("""
            SELECT o.*, m.role, m.department_id
            FROM enterprise_organization o JOIN enterprise_member m ON m.organization_id=o.id
            WHERE m.user_id=? AND m.status='active' AND o.status='active' ORDER BY o.create_time
            """, userId));
    }

    // 创建企业，并将创建者设为企业管理员（enterprise_admin）
    @PostMapping("/organizations")
    public Result<Map<String, Object>> createOrganization(@RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId();
        String name = required(body, "name", 100);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO enterprise_organization(id,name,owner_user_id) VALUES(?,?,?)", id, name, userId);
        jdbc.update("INSERT INTO enterprise_member(id,organization_id,user_id,role) VALUES(?,?,?,'enterprise_admin')", UUID.randomUUID().toString(), id, userId);
        audit.record(id, userId, "ORGANIZATION_CREATE", "organization", id, name);
        return Result.success(first("SELECT * FROM enterprise_organization WHERE id=?", id));
    }

    // 企业概览：按当前用户角色返回成员、部门、文件夹、文档及待办统计
    @GetMapping("/organizations/{id}/overview")
    public Result<Map<String, Object>> overview(@PathVariable String id) {
        Long userId = users.getRequiredUserId(); requireMember(id, userId);
        Map<String, Object> membership = permissions.membership(id, userId);
        boolean enterpriseAdmin = "enterprise_admin".equals(text(membership.get("role")));
        String departmentId = text(membership.get("department_id"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organization", first("SELECT * FROM enterprise_organization WHERE id=? AND status='active'", id));
        result.put("membership", membership);
        result.put("departments", jdbc.queryForList("SELECT * FROM enterprise_department WHERE organization_id=? ORDER BY create_time", id));
        if (enterpriseAdmin) {
            result.put("members", jdbc.queryForList("""
                SELECT member.*, registered_user.username, registered_user.nickname
                FROM enterprise_member member
                LEFT JOIN `user` registered_user ON registered_user.id=member.user_id
                WHERE member.organization_id=? AND member.status='active'
                ORDER BY member.create_time
                """, id));
        } else {
            result.put("members", jdbc.queryForList("""
                SELECT member.*, registered_user.username, registered_user.nickname
                FROM enterprise_member member
                LEFT JOIN `user` registered_user ON registered_user.id=member.user_id
                WHERE member.organization_id=? AND member.status='active' AND member.department_id=?
                ORDER BY member.create_time
                """, id, emptyToNull(departmentId)));
        }
        if (enterpriseAdmin) {
            result.put("folders", jdbc.queryForList("SELECT * FROM enterprise_folder WHERE organization_id=? ORDER BY create_time", id));
            result.put("documents", jdbc.queryForList("SELECT id,title,user_id,organization_id,department_id,folder_id,enterprise_access_level,status,update_time FROM document WHERE organization_id=? AND status='active' ORDER BY update_time DESC", id));
        } else {
            result.put("folders", jdbc.queryForList("SELECT * FROM enterprise_folder WHERE organization_id=? AND (visibility='organization' OR department_id=?) ORDER BY create_time", id, emptyToNull(departmentId)));
            result.put("documents", jdbc.queryForList("SELECT id,title,user_id,organization_id,department_id,folder_id,enterprise_access_level,status,update_time FROM document WHERE organization_id=? AND status='active' AND (department_id IS NULL OR department_id=?) ORDER BY update_time DESC", id, emptyToNull(departmentId)));
        }
        result.put("pendingApprovals", count("SELECT COUNT(*) FROM document_approval WHERE organization_id=? AND approver_user_id=? AND status='pending'", id, userId));
        result.put("unreadNotifications", count("SELECT COUNT(*) FROM enterprise_notification WHERE user_id=? AND is_read=0", userId));
        return Result.success(result);
    }

    // 创建部门（仅企业管理员可操作）
    @PostMapping("/organizations/{id}/departments")
    public Result<Map<String, Object>> createDepartment(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId(); permissions.assertEnterpriseAdmin(id, userId);
        String departmentId = UUID.randomUUID().toString(), name = required(body, "name", 80);
        jdbc.update("INSERT INTO enterprise_department(id,organization_id,name) VALUES(?,?,?)", departmentId, id, name);
        audit.record(id, userId, "DEPARTMENT_CREATE", "department", departmentId, name);
        return Result.success(first("SELECT * FROM enterprise_department WHERE id=?", departmentId));
    }

    // 添加成员：分配角色与部门，重复加入则恢复并更新其角色
    @PostMapping("/organizations/{id}/members")
    public Result<Map<String, Object>> addMember(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Long operator = users.getRequiredUserId(); permissions.assertAdmin(id, operator);
        Long memberUserId = longValue(body.get("userId"), "用户ID不能为空");
        ensureUser(memberUserId);
        String role = normalizeRole(body.get("role"));
        String departmentId = text(body.get("departmentId"));
        assertMemberManagementScope(id, operator, role, departmentId, null);
        validateDepartment(id, departmentId);
        List<Map<String, Object>> exists = jdbc.queryForList("SELECT * FROM enterprise_member WHERE organization_id=? AND user_id=?", id, memberUserId);
        if (exists.isEmpty()) jdbc.update("INSERT INTO enterprise_member(id,organization_id,user_id,department_id,role) VALUES(?,?,?,?,?)", UUID.randomUUID().toString(), id, memberUserId, emptyToNull(departmentId), role);
        else jdbc.update("UPDATE enterprise_member SET department_id=?,role=?,status='active' WHERE organization_id=? AND user_id=?", emptyToNull(departmentId), role, id, memberUserId);
        notifyUser(id, memberUserId, "ENTERPRISE_INVITE", "你已加入企业空间", "企业管理员已将你加入文档空间", id);
        audit.record(id, operator, "MEMBER_ADD", "user", String.valueOf(memberUserId), role);
        return Result.success(first("SELECT * FROM enterprise_member WHERE organization_id=? AND user_id=?", id, memberUserId));
    }

    // 更新成员的角色与所属部门
    @PutMapping("/organizations/{id}/members/{memberUserId}")
    public Result<Void> updateMember(@PathVariable String id, @PathVariable Long memberUserId, @RequestBody Map<String, Object> body) {
        Long operator = users.getRequiredUserId(); permissions.assertAdmin(id, operator);
        String role = normalizeRole(body.get("role"));
        String departmentId = text(body.get("departmentId"));
        assertMemberManagementScope(id, operator, role, departmentId, memberUserId);
        validateDepartment(id, departmentId);
        jdbc.update("UPDATE enterprise_member SET department_id=?,role=? WHERE organization_id=? AND user_id=?",
                emptyToNull(departmentId), role, id, memberUserId);
        audit.record(id, operator, "MEMBER_UPDATE", "user", String.valueOf(memberUserId), String.valueOf(body));
        return Result.success();
    }

    // 移除成员（软删除，状态置为 removed；禁止移除企业创建者）
    @DeleteMapping("/organizations/{id}/members/{memberUserId}")
    public Result<Void> removeMember(@PathVariable String id, @PathVariable Long memberUserId) {
        Long operator = users.getRequiredUserId(); permissions.assertAdmin(id, operator);
        Map<String, Object> org = first("SELECT * FROM enterprise_organization WHERE id=?", id);
        if (Objects.equals(String.valueOf(org.get("owner_user_id")), String.valueOf(memberUserId))) throw new BusinessException("不能移除企业创建者");
        Map<String, Object> target = permissions.membership(id, memberUserId);
        if (target == null) throw new BusinessException("成员不存在");
        assertMemberManagementScope(id, operator, text(target.get("role")), text(target.get("department_id")), memberUserId);
        jdbc.update("UPDATE enterprise_member SET status='removed' WHERE organization_id=? AND user_id=?", id, memberUserId);
        audit.record(id, operator, "MEMBER_REMOVE", "user", String.valueOf(memberUserId), "removed");
        return Result.success();
    }

    // 强制成员下线：调用用户服务删除该成员的全部会话
    @PostMapping("/organizations/{id}/members/{memberUserId}/force-logout")
    public Result<Void> forceMemberLogout(@PathVariable String id, @PathVariable Long memberUserId) {
        Long operator = users.getRequiredUserId();
        permissions.assertEnterpriseAdmin(id, operator);
        requireMember(id, memberUserId);
        HttpHeaders headers = internalHeaders();
        rest.exchange(userServiceUrl + "/api/internal/users/" + memberUserId + "/sessions",
                HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        audit.record(id, operator, "MEMBER_FORCE_LOGOUT", "user", String.valueOf(memberUserId), "all sessions revoked");
        return Result.success();
    }

    // 创建文件夹：按企业/部门管理员设置可见范围与可写角色
    @PostMapping("/organizations/{id}/folders")
    public Result<Map<String, Object>> createFolder(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId(); permissions.assertAdmin(id, userId);
        Map<String, Object> membership = permissions.membership(id, userId);
        String departmentId = text(body.get("departmentId"));
        if (!permissions.isEnterpriseAdmin(id, userId)) {
            departmentId = text(membership.get("department_id"));
            if (departmentId.isBlank()) throw new BusinessException("部门管理员未绑定部门");
        }
        validateDepartment(id, departmentId);
        String folderId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO enterprise_folder(id,organization_id,department_id,parent_id,name,visibility,write_role,created_by) VALUES(?,?,?,?,?,?,?,?)",
                folderId, id, emptyToNull(departmentId), emptyToNull(text(body.get("parentId"))), required(body, "name", 100),
                permissions.isEnterpriseAdmin(id, userId) ? choice(body.get("visibility"), Set.of("organization", "department"), "organization") : "department",
                choice(body.get("writeRole"), Set.of("member", "department_admin", "enterprise_admin"), "member"), userId);
        audit.record(id, userId, "FOLDER_CREATE", "folder", folderId, text(body.get("name")));
        return Result.success(first("SELECT * FROM enterprise_folder WHERE id=?", folderId));
    }

    // 将文档移入企业空间，并设置其部门、文件夹与访问级别
    @PutMapping("/documents/{documentId}/location")
    public Result<Void> moveDocument(@PathVariable String documentId, @RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId(); Document doc = requiredDocument(documentId);
        boolean owner = documentAccess.isOwner(doc, userId);
        boolean currentEnterpriseAdmin = doc.getOrganizationId() != null && permissions.isAdmin(doc.getOrganizationId(), userId);
        if (!owner && !currentEnterpriseAdmin) documentAccess.assertCanWrite(doc, userId);
        String organizationId = required(body, "organizationId", 64); requireMember(organizationId, userId);
        if (doc.getOrganizationId() != null && !doc.getOrganizationId().equals(organizationId)) {
            permissions.assertEnterpriseAdmin(doc.getOrganizationId(), userId);
        }
        String departmentId = text(body.get("departmentId"));
        String folderId = text(body.get("folderId"));
        String accessLevel = enterpriseAccessLevel(body.get("accessLevel"), doc.getEnterpriseAccessLevel());
        validateDocumentLocation(organizationId, userId, departmentId, folderId);
        jdbc.update("UPDATE document SET organization_id=?,department_id=?,folder_id=?,enterprise_access_level=?,update_time=NOW() WHERE id=?",
                organizationId, emptyToNull(departmentId), emptyToNull(folderId), accessLevel, documentId);
        syncDocumentTenantToChildren(documentId, organizationId);
        audit.record(organizationId, userId, "DOCUMENT_MOVE", "document", documentId, String.valueOf(body));
        return Result.success();
    }

    // 批量将个人文档同步到企业空间（仅文档所有者可同步）
    @PutMapping("/documents/sync")
    @Transactional
    public Result<Map<String, Object>> syncDocuments(@RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId();
        List<String> documentIds = stringList(body == null ? null : body.get("documentIds"));
        if (documentIds.isEmpty()) throw new BusinessException("请选择要同步的文档");
        if (documentIds.size() > 100) throw new BusinessException("单次最多同步 100 份文档");
        String organizationId = required(body, "organizationId", 64);
        String departmentId = text(body.get("departmentId"));
        String folderId = text(body.get("folderId"));
        String accessLevel = enterpriseAccessLevel(body.get("accessLevel"), "edit");
        validateDocumentLocation(organizationId, userId, departmentId, folderId);
        for (String documentId : documentIds) {
            Document document = requiredDocument(documentId);
            if (!documentAccess.isOwner(document, userId)) throw new BusinessException("只有文档所有者可以同步《" + document.getTitle() + "》");
            if (!"active".equals(document.getStatus())) throw new BusinessException("回收站文档不能同步到企业空间");
        }
        for (String documentId : documentIds) {
            jdbc.update("UPDATE document SET organization_id=?,department_id=?,folder_id=?,enterprise_access_level=?,update_time=NOW() WHERE id=?",
                    organizationId, emptyToNull(departmentId), emptyToNull(folderId), accessLevel, documentId);
            syncDocumentTenantToChildren(documentId, organizationId);
            audit.record(organizationId, userId, "DOCUMENT_SYNC", "document", documentId,
                    "department=" + departmentId + ",folder=" + folderId + ",access=" + accessLevel);
        }
        notifyDocumentSyncMembers(organizationId, departmentId, userId, documentIds.size());
        return Result.success(Map.of("synced", documentIds.size(), "organizationId", organizationId));
    }

    // 将文档移出企业空间，个人原件保留
    @DeleteMapping("/documents/{documentId}/location")
    public Result<Void> removeDocumentFromEnterprise(@PathVariable String documentId) {
        Long userId = users.getRequiredUserId();
        Document document = requiredDocument(documentId);
        String organizationId = document.getOrganizationId();
        if (organizationId == null || organizationId.isBlank()) throw new BusinessException("文档尚未同步到企业空间");
        if (!documentAccess.isOwner(document, userId) && !permissions.isEnterpriseAdmin(organizationId, userId)) {
            throw new BusinessException("只有文档所有者或企业管理员可以移出企业空间");
        }
        jdbc.update("UPDATE document SET organization_id=NULL,department_id=NULL,folder_id=NULL,enterprise_access_level='edit',update_time=NOW() WHERE id=?", documentId);
        syncDocumentTenantToChildren(documentId, null);
        audit.record(organizationId, userId, "DOCUMENT_UNSYNC", "document", documentId, "移出企业空间，个人原件保留");
        return Result.success();
    }

    // 提交文档审批请求并通知审批人
    @PostMapping("/approvals")
    public Result<Map<String, Object>> submitApproval(@RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId(); String documentId = required(body, "documentId", 64);
        Document doc = requiredDocument(documentId); documentAccess.assertCanRead(doc, userId);
        Long approver = longValue(body.get("approverUserId"), "审批人ID不能为空");
        if (doc.getOrganizationId() != null) requireMember(doc.getOrganizationId(), approver);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO document_approval(id,organization_id,document_id,approval_type,applicant_user_id,approver_user_id) VALUES(?,?,?,?,?,?)",
                id, doc.getOrganizationId(), documentId, choice(body.get("approvalType"), Set.of("publish", "review", "contract", "archive"), "review"), userId, approver);
        notifyUser(doc.getOrganizationId(), approver, "APPROVAL_PENDING", "收到新的文档审批", doc.getTitle(), id);
        audit.record(doc.getOrganizationId(), userId, "APPROVAL_SUBMIT", "approval", id, doc.getTitle());
        return Result.success(first("SELECT * FROM document_approval WHERE id=?", id));
    }

    @GetMapping("/approvals")
    public Result<List<Map<String, Object>>> approvals() {
        Long userId = users.getRequiredUserId();
        return Result.success(jdbc.queryForList("""
            SELECT approval.*, document.title document_title,
                   applicant.username applicant_username,
                   approver.username approver_username
            FROM document_approval approval
            LEFT JOIN document ON document.id=approval.document_id
            LEFT JOIN `user` applicant ON applicant.id=approval.applicant_user_id
            LEFT JOIN `user` approver ON approver.id=approval.approver_user_id
            WHERE approval.approver_user_id=? OR approval.applicant_user_id=?
            ORDER BY approval.create_time DESC
            """, userId, userId));
    }

    // 审批决策：仅指定审批人可处理，结果写入并通知申请人
    @PostMapping("/approvals/{id}/decision")
    public Result<Void> decide(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId(); Map<String, Object> approval = first("SELECT * FROM document_approval WHERE id=?", id);
        if (!Objects.equals(String.valueOf(approval.get("approver_user_id")), String.valueOf(userId))) throw new BusinessException("只有指定审批人可以处理");
        if (!"pending".equals(approval.get("status"))) throw new BusinessException("该审批已处理");
        String decision = choice(body.get("decision"), Set.of("approved", "rejected"), null);
        if (decision == null) throw new BusinessException("审批结果不合法");
        jdbc.update("UPDATE document_approval SET status=?,comment=?,decision_time=NOW() WHERE id=? AND status='pending'", decision, text(body.get("comment")), id);
        notifyUser(text(approval.get("organization_id")), ((Number) approval.get("applicant_user_id")).longValue(), "APPROVAL_DECISION", "文档审批已处理", decision, id);
        audit.record(text(approval.get("organization_id")), userId, "APPROVAL_DECISION", "approval", id, decision);
        return Result.success();
    }

    @GetMapping("/notifications")
    public Result<List<Map<String, Object>>> notifications() {
        Long userId = users.getRequiredUserId();
        return Result.success(jdbc.queryForList("SELECT * FROM enterprise_notification WHERE user_id=? ORDER BY create_time DESC LIMIT 100", userId));
    }

    @PutMapping("/notifications/{id}/read")
    public Result<Void> readNotification(@PathVariable String id) {
        jdbc.update("UPDATE enterprise_notification SET is_read=1 WHERE id=? AND user_id=?", id, users.getRequiredUserId());
        return Result.success();
    }

    @GetMapping("/organizations/{id}/audits")
    public Result<List<Map<String, Object>>> audits(@PathVariable String id) {
        Long userId = users.getRequiredUserId(); permissions.assertEnterpriseAdmin(id, userId);
        return Result.success(jdbc.queryForList("""
            SELECT audit.*, registered_user.username
            FROM enterprise_audit_log audit
            LEFT JOIN `user` registered_user ON registered_user.id=audit.user_id
            WHERE audit.organization_id=?
            ORDER BY audit.create_time DESC LIMIT 300
            """, id));
    }

    // 企业统计：汇总成员、部门、文档、审批、分享及活动趋势等数据
    @GetMapping("/organizations/{id}/statistics")
    public Result<Map<String, Object>> statistics(@PathVariable String id) {
        Long userId = users.getRequiredUserId(); permissions.assertEnterpriseAdmin(id, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("members", count("SELECT COUNT(*) FROM enterprise_member WHERE organization_id=? AND status='active'", id));
        summary.put("departments", count("SELECT COUNT(*) FROM enterprise_department WHERE organization_id=?", id));
        summary.put("documents", count("SELECT COUNT(*) FROM document WHERE organization_id=? AND status='active'", id));
        summary.put("pendingApprovals", count("SELECT COUNT(*) FROM document_approval WHERE organization_id=? AND status='pending'", id));
        summary.put("activeShares", count("SELECT COUNT(*) FROM document_secure_share s JOIN document d ON d.id=s.document_id WHERE d.organization_id=? AND s.status='active' AND (s.expires_at IS NULL OR s.expires_at>NOW())", id));
        summary.put("operations30d", count("SELECT COUNT(*) FROM enterprise_audit_log WHERE organization_id=? AND create_time>=DATE_SUB(NOW(),INTERVAL 30 DAY)", id));
        result.put("summary", summary);
        result.put("roles", jdbc.queryForList("SELECT role name,COUNT(*) value FROM enterprise_member WHERE organization_id=? AND status='active' GROUP BY role ORDER BY value DESC", id));
        result.put("departmentDocuments", jdbc.queryForList("""
            SELECT dep.id,dep.name,COUNT(doc.id) value FROM enterprise_department dep
            LEFT JOIN document doc ON doc.department_id=dep.id AND doc.organization_id=dep.organization_id AND doc.status='active'
            WHERE dep.organization_id=? GROUP BY dep.id,dep.name ORDER BY value DESC
            """, id));
        result.put("approvalStatus", jdbc.queryForList("SELECT status name,COUNT(*) value FROM document_approval WHERE organization_id=? GROUP BY status", id));
        result.put("documentTrend", jdbc.queryForList("""
            SELECT DATE(create_time) day,COUNT(*) value FROM document
            WHERE organization_id=? AND create_time>=DATE_SUB(CURDATE(),INTERVAL 13 DAY)
            GROUP BY DATE(create_time) ORDER BY day
            """, id));
        result.put("activityTrend", jdbc.queryForList("""
            SELECT DATE(create_time) day,COUNT(*) value FROM enterprise_audit_log
            WHERE organization_id=? AND create_time>=DATE_SUB(CURDATE(),INTERVAL 13 DAY)
            GROUP BY DATE(create_time) ORDER BY day
            """, id));
        result.put("topUsers", jdbc.queryForList("""
            SELECT audit.user_id, registered_user.username, COUNT(*) operations
            FROM enterprise_audit_log audit
            LEFT JOIN `user` registered_user ON registered_user.id=audit.user_id
            WHERE audit.organization_id=? AND audit.create_time>=DATE_SUB(NOW(),INTERVAL 30 DAY)
            GROUP BY audit.user_id,registered_user.username
            ORDER BY operations DESC LIMIT 8
            """, id));
        return Result.success(result);
    }

    private void requireMember(String organizationId, Long userId) { if (permissions.membership(organizationId, userId) == null) throw new BusinessException("你不是该企业成员"); }
    private void assertMemberManagementScope(String organizationId, Long operator, String targetRole, String departmentId, Long targetUserId) {
        if (permissions.isEnterpriseAdmin(organizationId, operator)) return;
        Map<String, Object> operatorMember = permissions.membership(organizationId, operator);
        String operatorDepartment = text(operatorMember.get("department_id"));
        if (operatorDepartment.isBlank() || !operatorDepartment.equals(departmentId)) throw new BusinessException("部门管理员只能管理本部门成员");
        if (!Set.of("member", "guest").contains(targetRole)) throw new BusinessException("部门管理员不能授予管理员角色");
        if (targetUserId != null && Objects.equals(operator, targetUserId)) throw new BusinessException("不能修改或移除自己的成员身份");
    }
    private void validateDepartment(String organizationId, String departmentId) {
        if (departmentId == null || departmentId.isBlank()) return;
        if (count("SELECT COUNT(*) FROM enterprise_department WHERE id=? AND organization_id=?", departmentId, organizationId) == 0) {
            throw new BusinessException("部门不属于当前企业");
        }
    }
    private void validateDocumentLocation(String organizationId, Long userId, String departmentId, String folderId) {
        permissions.assertCanPlace(organizationId, departmentId, folderId, userId);
    }
    private Document requiredDocument(String id) { Document d = documentMapper.selectById(id); if (d == null) throw new BusinessException("文档不存在"); return d; }
    private Map<String, Object> first(String sql, Object... args) { List<Map<String, Object>> rows = jdbc.queryForList(sql, args); if (rows.isEmpty()) throw new BusinessException("数据不存在"); return rows.get(0); }
    private int count(String sql, Object... args) { Number n = jdbc.queryForObject(sql, Number.class, args); return n == null ? 0 : n.intValue(); }
    private String required(Map<String, Object> body, String key, int max) { String value = text(body == null ? null : body.get(key)); if (value.isBlank() || value.length() > max) throw new BusinessException(key + "不能为空且长度不能超过" + max); return value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private Long longValue(Object value, String message) { try { return Long.valueOf(text(value)); } catch (Exception e) { throw new BusinessException(message); } }
    private String normalizeRole(Object value) { String role = text(value); if (!MEMBER_ROLES.contains(role)) throw new BusinessException("成员角色不合法"); return role; }
    private String choice(Object value, Set<String> allowed, String fallback) { String candidate = text(value); return allowed.contains(candidate) ? candidate : fallback; }
    private String enterpriseAccessLevel(Object value, String fallback) { return choice(value, Set.of("read", "comment", "edit"), fallback == null || fallback.isBlank() ? "edit" : fallback); }
    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> values)) return Collections.emptyList();
        return values.stream().map(this::text).filter(item -> !item.isBlank()).distinct().toList();
    }
    private void notifyUser(String organizationId, Long userId, String type, String title, String content, String relatedId) {
        jdbc.update("INSERT INTO enterprise_notification(id,organization_id,user_id,type,title,content,related_id) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), emptyToNull(organizationId), userId, type, title, content, relatedId);
    }
    private void notifyDocumentSyncMembers(String organizationId, String departmentId, Long operator, int count) {
        List<Long> recipients;
        if (departmentId == null || departmentId.isBlank()) {
            recipients = jdbc.query("SELECT user_id FROM enterprise_member WHERE organization_id=? AND status='active' AND user_id<>?", (rs, row) -> rs.getLong(1), organizationId, operator);
        } else {
            recipients = jdbc.query("SELECT user_id FROM enterprise_member WHERE organization_id=? AND status='active' AND user_id<>? AND (department_id=? OR role='enterprise_admin')", (rs, row) -> rs.getLong(1), organizationId, operator, departmentId);
        }
        String content = count == 1 ? "企业空间新增 1 份同步文档" : "企业空间新增 " + count + " 份同步文档";
        recipients.stream().distinct().forEach(userId -> notifyUser(organizationId, userId, "DOCUMENT_SYNC", "企业文档已更新", content, organizationId));
    }
    // 将企业（租户）标识同步到文档的版本、权限、评论等全部关联子表
    private void syncDocumentTenantToChildren(String documentId, String organizationId) {
        String tenant = emptyToNull(organizationId);
        jdbc.update("UPDATE document_version SET organization_id=? WHERE document_id=?", tenant, documentId);
        jdbc.update("UPDATE document_access SET organization_id=? WHERE document_id=?", tenant, documentId);
        jdbc.update("UPDATE document_comment SET organization_id=? WHERE document_id=?", tenant, documentId);
        jdbc.update("UPDATE document_annotation SET organization_id=? WHERE document_id=?", tenant, documentId);
        jdbc.update("UPDATE document_suggestion SET organization_id=? WHERE document_id=?", tenant, documentId);
        jdbc.update("UPDATE document_secure_share SET organization_id=? WHERE document_id=?", tenant, documentId);
        jdbc.update("UPDATE file_metadata f JOIN document d ON d.file_id=f.id SET f.organization_id=? WHERE d.id=?", tenant, documentId);
    }
    private void ensureUser(Long userId) { try { Map<?,?> response = rest.exchange(userServiceUrl + "/api/internal/users/" + userId, HttpMethod.GET, new HttpEntity<>(internalHeaders()), Map.class).getBody(); if (response == null || !"200".equals(String.valueOf(response.get("code")))) throw new BusinessException("用户不存在"); } catch (BusinessException e) { throw e; } catch (Exception e) { throw new BusinessException("用户不存在或用户服务不可用"); } }
    private HttpHeaders internalHeaders() { HttpHeaders headers = new HttpHeaders(); headers.set("X-Internal-Service-Token", internalServiceTokenProvider.getRequiredToken()); return headers; }
}
