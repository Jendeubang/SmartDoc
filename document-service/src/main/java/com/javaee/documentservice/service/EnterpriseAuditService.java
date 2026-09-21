package com.javaee.documentservice.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Service
public class EnterpriseAuditService {
    private final JdbcTemplate jdbc;
    public EnterpriseAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String organizationId, Long userId, String action, String targetType, String targetId, String detail) {
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) ip = attrs.getRequest().getRemoteAddr();
        jdbc.update("INSERT INTO enterprise_audit_log(id,organization_id,user_id,action,target_type,target_id,detail,ip_address) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), organizationId, userId, action, targetType, targetId, detail, ip);
    }
}
