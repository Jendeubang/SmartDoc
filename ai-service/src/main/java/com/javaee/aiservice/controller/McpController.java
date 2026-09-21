package com.javaee.aiservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.javaee.aiservice.agent.execution.approval.AgentApprovalService;
import com.javaee.aiservice.agent.execution.tool.AgentToolRegistry;
import com.javaee.aiservice.mcp.McpAuditLog;
import com.javaee.aiservice.mcp.McpAuditLogMapper;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 控制平面控制器
 * 提供审计日志查询、工具权限列表、审批状态等接口
 */
@RestController
@RequestMapping("/api/ai/mcp")
@Tag(name = "MCP控制平面", description = "模型控制平面：审计、权限、审批管理")
public class McpController {

    @Autowired(required = false)
    private McpAuditLogMapper mcpAuditLogMapper;

    @Autowired
    private AgentToolRegistry toolRegistry;

    @Autowired
    private AgentApprovalService agentApprovalService;

    @Autowired
    private RequestUserContext requestUserContext;

    @GetMapping("/audit-logs")
    @Operation(summary = "查询审计日志", description = "查询MCP审计日志列表，支持按用户和操作筛选")
    public Result<List<McpAuditLog>> listAuditLogs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "50") int limit) {
        if (mcpAuditLogMapper == null) {
            return Result.success(List.of());
        }
        QueryWrapper<McpAuditLog> qw = new QueryWrapper<>();
        if (userId != null && !userId.isBlank()) {
            qw.eq("user_id", userId);
        }
        if (action != null && !action.isBlank()) {
            qw.eq("action", action);
        }
        qw.orderByDesc("create_time").last("LIMIT " + Math.min(limit, 200));
        List<McpAuditLog> logs = mcpAuditLogMapper.selectList(qw);
        return Result.success(logs);
    }

    @GetMapping("/tools")
    @Operation(summary = "获取工具列表", description = "获取所有已注册的工具及其权限要求")
    public Result<List<Map<String, Object>>> listTools() {
        List<Map<String, Object>> tools = toolRegistry.list().stream().map(t -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("destructive", t.isDestructive());
            m.put("category", t.getCategory());
            m.put("parameters", t.getParameters());
            return m;
        }).collect(Collectors.toList());
        return Result.success(tools);
    }

    @GetMapping("/approvals/pending")
    @Operation(summary = "查询待审批列表", description = "查询当前用户待审批的危险操作")
    public Result<List<String>> listPendingApprovals() {
        String userId = requestUserContext.getRequiredUserId();
        // Redis 扫描所有审批令牌成本较高，暂返回空列表
        return Result.success(List.of());
    }

    @GetMapping("/health")
    @Operation(summary = "MCP 健康检查", description = "检查 MCP 各组件状态")
    public Result<Map<String, Object>> health() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("status", "UP");
        status.put("auditLogEnabled", mcpAuditLogMapper != null);
        status.put("toolsCount", toolRegistry.list().size());
        status.put("timestamp", System.currentTimeMillis());
        return Result.success(status);
    }
}
