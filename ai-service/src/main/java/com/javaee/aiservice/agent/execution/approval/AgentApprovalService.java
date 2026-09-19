package com.javaee.aiservice.agent.execution.approval;

/**
 * 【简历：危险操作审批服务】
 * 支持 Agent 危险操作（如删除文件）的审批流程，
 * 提升 Agent 调用的安全性和可控性。
 */

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side approval store for destructive Agent tools.
 */
// 类职责：危险操作审批服务（简历第1条 Agent 执行框架的安全可控环节）——危险工具执行前生成一次性审批 token 存 Redis 挂起等待，用户批准时校验参数指纹并消费 token。
@Service
public class AgentApprovalService {

    private static final String PREFIX = "agent:approval:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${ai.agent.approval-expiry-seconds:300}")
    private long approvalExpirySeconds;

    // 创建审批挑战：为本次危险操作生成一次性 token，把参数指纹写入 Redis 并设置过期时间，返回给前端待用户批准。
    public ApprovalChallenge createChallenge(String userId, String toolName, Map<String, Object> params) {
        String token = UUID.randomUUID().toString();
        String fingerprint = fingerprint(userId, toolName, params);
        redisTemplate.opsForValue().set(PREFIX + token, fingerprint, Duration.ofSeconds(approvalExpirySeconds));
        return new ApprovalChallenge(token, System.currentTimeMillis() + approvalExpirySeconds * 1000);
    }

    // 校验并消费审批：核对 token 对应指纹与本次参数是否一致，一致则删除 token（一次性使用）放行，否则返回失败原因。
    public ApprovalResult verifyAndConsumeDetailed(String token, String userId, String toolName, Map<String, Object> params) {
        if (token == null || token.isBlank()) {
            return ApprovalResult.MISSING;
        }
        String key = PREFIX + token;
        Object expected = redisTemplate.opsForValue().get(key);
        if (expected == null) {
            return ApprovalResult.EXPIRED_OR_USED;
        }
        String actual = fingerprint(userId, toolName, params);
        if (!expected.toString().equals(actual)) {
            return ApprovalResult.PARAMS_MISMATCH;
        }
        redisTemplate.delete(key);
        return ApprovalResult.OK;
    }

    public boolean verifyAndConsume(String token, String userId, String toolName, Map<String, Object> params) {
        return verifyAndConsumeDetailed(token, userId, toolName, params) == ApprovalResult.OK;
    }

    public boolean cancel(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.delete(PREFIX + token));
    }

    // 生成参数指纹：对用户、工具名和关键参数做 SHA-256 摘要，防止审批通过后参数被篡改。
    private String fingerprint(String userId, String toolName, Map<String, Object> params) {
        String objectName = String.valueOf(params.getOrDefault("objectName", ""));
        String targetVersionId = String.valueOf(params.getOrDefault("targetVersionId", ""));
        String recycleId = String.valueOf(params.getOrDefault("recycleId", ""));
        String raw = userId + "|" + toolName + "|" + objectName + "|" + targetVersionId + "|" + recycleId;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成审批指纹失败", e);
        }
    }

    public record ApprovalChallenge(String token, long expiresAt) {
    }

    public enum ApprovalResult {
        OK,
        MISSING,
        EXPIRED_OR_USED,
        PARAMS_MISMATCH
    }
}
