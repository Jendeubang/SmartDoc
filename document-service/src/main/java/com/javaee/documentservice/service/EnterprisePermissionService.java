package com.javaee.documentservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.entity.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

// 企业权限服务：负责企业/部门/文件夹三级权限判定（membership、isAdmin、canRead、canWrite、canComment）。
// 对应简历第 6 条「企业空间与在线协同」中的角色权限控制。
@Service
public class EnterprisePermissionService {
    private final JdbcTemplate jdbc;
    public EnterprisePermissionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // 查询用户在指定企业中的有效成员记录（含角色与部门信息）
    public Map<String, Object> membership(String organizationId, Long userId) {
        if (organizationId == null || userId == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM enterprise_member WHERE organization_id=? AND user_id=? AND status='active'", organizationId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // 判断用户是否为管理员（企业管理员或部门管理员）
    public boolean isAdmin(String organizationId, Long userId) {
        Map<String, Object> member = membership(organizationId, userId);
        if (member == null) return false;
        String role = String.valueOf(member.get("role"));
        return "enterprise_admin".equals(role) || "department_admin".equals(role);
    }

    // 判断用户是否为企业管理员
    public boolean isEnterpriseAdmin(String organizationId, Long userId) {
        Map<String, Object> member = membership(organizationId, userId);
        return member != null && "enterprise_admin".equals(String.valueOf(member.get("role")));
    }

    public void assertAdmin(String organizationId, Long userId) {
        if (!isAdmin(organizationId, userId)) throw new BusinessException("仅企业或部门管理员可执行此操作");
    }

    public void assertEnterpriseAdmin(String organizationId, Long userId) {
        if (!isEnterpriseAdmin(organizationId, userId)) throw new BusinessException("仅企业管理员可执行此操作");
    }

    // 企业级读权限：企业管理员可读全部，其余成员仅能读本部门或无部门文档
    public boolean canRead(Document document, Long userId) {
        Map<String, Object> member = membership(document.getOrganizationId(), userId);
        if (member == null) return false;
        if (document.getDepartmentId() == null) return true;
        String role = String.valueOf(member.get("role"));
        return "enterprise_admin".equals(role) || document.getDepartmentId().equals(member.get("department_id"));
    }

    // 企业级写权限：结合角色、访问级别、部门归属与文件夹写权限综合判定
    public boolean canWrite(Document document, Long userId) {
        Map<String, Object> member = membership(document.getOrganizationId(), userId);
        if (member == null || "guest".equals(String.valueOf(member.get("role")))) return false;
        if (!"edit".equals(normalizeAccessLevel(document.getEnterpriseAccessLevel()))) return false;
        String role = String.valueOf(member.get("role"));
        if ("enterprise_admin".equals(role)) return true;
        if (document.getDepartmentId() != null && !document.getDepartmentId().equals(member.get("department_id"))) return false;
        if ("department_admin".equals(role)) return true;
        if (document.getFolderId() == null) return true;
        List<String> roles = jdbc.query("SELECT write_role FROM enterprise_folder WHERE id=?", (rs, n) -> rs.getString(1), document.getFolderId());
        return roles.isEmpty() || roleRank(role) >= roleRank(roles.get(0));
    }

    // 企业级评论权限：先要求可读，且文档访问级别非只读
    public boolean canComment(Document document, Long userId) {
        if (!canRead(document, userId)) return false;
        return !"read".equals(normalizeAccessLevel(document.getEnterpriseAccessLevel()));
    }

    // 校验文档能否放入指定企业/部门/文件夹（校验成员身份、部门归属与文件夹写权限）
    public void assertCanPlace(String organizationId, String departmentId, String folderId, Long userId) {
        if (organizationId == null || organizationId.isBlank()) {
            if ((departmentId != null && !departmentId.isBlank()) || (folderId != null && !folderId.isBlank())) {
                throw new BusinessException("未选择企业时不能设置部门或文件夹");
            }
            return;
        }
        Map<String, Object> member = membership(organizationId, userId);
        if (member == null) throw new BusinessException("你不是该企业成员");
        String role = String.valueOf(member.get("role"));
        if ("guest".equals(role)) throw new BusinessException("访客不能写入企业文档");
        String normalizedDepartment = blankToNull(departmentId);
        if (normalizedDepartment != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM enterprise_department WHERE id=? AND organization_id=?",
                Integer.class, normalizedDepartment, organizationId) == 0) {
            throw new BusinessException("部门不属于当前企业");
        }
        if (!"enterprise_admin".equals(role) && !Objects.equals(normalizedDepartment, blankToNull(String.valueOf(member.get("department_id"))))) {
            throw new BusinessException("只能写入自己所在部门");
        }
        if (folderId != null && !folderId.isBlank()) {
            List<Map<String, Object>> folders = jdbc.queryForList("SELECT * FROM enterprise_folder WHERE id=? AND organization_id=?", folderId, organizationId);
            if (folders.isEmpty()) throw new BusinessException("文件夹不属于当前企业");
            Map<String, Object> folder = folders.get(0);
            if (!Objects.equals(blankToNull(String.valueOf(folder.get("department_id"))), normalizedDepartment)) {
                throw new BusinessException("文件夹与所选部门不匹配");
            }
            if (roleRank(role) < roleRank(String.valueOf(folder.get("write_role")))) {
                throw new BusinessException("当前角色无权写入该文件夹");
            }
        }
    }

    // 将角色映射为权限等级，等级越高权限越大（企业管理员 > 部门管理员 > 成员）
    private int roleRank(String role) {
        return switch (role == null ? "" : role) {
            case "enterprise_admin" -> 3;
            case "department_admin" -> 2;
            case "member" -> 1;
            default -> 0;
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value;
    }

    private String normalizeAccessLevel(String value) {
        return value == null || value.isBlank() ? "edit" : value;
    }
}
