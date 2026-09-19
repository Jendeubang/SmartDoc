package com.javaee.user.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.utils.JwtUtils;
import com.javaee.user.entity.User;
import com.javaee.user.vo.LoginVO;
import com.javaee.user.vo.RefreshTokenVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

// 【简历第6条 · Redis Refresh Token】认证会话服务：用 Redis 管理登录会话与刷新令牌指纹，
// 支持刷新令牌轮换、注销、管理员强制下线（递增会话版本号使该用户所有已签发令牌立即失效）。
@Service
public class AuthSessionService {
    private static final Duration REFRESH_TTL = Duration.ofDays(7);
    /** Compare-and-swap the refresh hash in Redis so two concurrent refreshes cannot both succeed. */
    private static final DefaultRedisScript<Long> ROTATE_REFRESH_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3])
                return 1
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public AuthSessionService(StringRedisTemplate redis) { this.redis = redis; }

    // 登录后创建会话：生成 sessionId、写入 Redis，签发访问令牌与刷新令牌并返回
    public LoginVO createSession(User user) {
        long version = currentVersion(user.getId());
        String sessionId = UUID.randomUUID().toString();
        redis.opsForValue().set(sessionKey(sessionId), String.valueOf(user.getId()), REFRESH_TTL);
        redis.opsForSet().add(userSessionsKey(user.getId()), sessionId);
        redis.expire(userSessionsKey(user.getId()), REFRESH_TTL);
        String refresh = JwtUtils.generateRefreshToken(user.getId(), sessionId, version);
        redis.opsForValue().set(refreshKey(sessionId), JwtUtils.sha256(refresh), REFRESH_TTL);
        String access = JwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole(), sessionId, version);
        LoginVO result = new LoginVO();
        result.setAccessToken(access);
        result.setRefreshToken(refresh);
        return result;
    }

    // 刷新令牌轮换：校验旧刷新令牌、比对 Redis 存储的哈希与会话版本，通过后签发新令牌对
    public RefreshTokenVO rotate(User user, String oldRefreshToken) {
        if (!JwtUtils.validateRefreshToken(oldRefreshToken)) throw new BusinessException("刷新令牌无效或已过期");
        String sessionId = JwtUtils.getSessionId(oldRefreshToken);
        long tokenVersion = JwtUtils.getSessionVersion(oldRefreshToken);
        String owner = redis.opsForValue().get(sessionKey(sessionId));
        if (!String.valueOf(user.getId()).equals(owner) || tokenVersion != currentVersion(user.getId())) {
            throw new BusinessException("刷新令牌已失效");
        }
        String newRefresh = JwtUtils.generateRefreshToken(user.getId(), sessionId, tokenVersion);
        Long rotated = redis.execute(ROTATE_REFRESH_SCRIPT,
                Collections.singletonList(refreshKey(sessionId)),
                JwtUtils.sha256(oldRefreshToken), JwtUtils.sha256(newRefresh),
                String.valueOf(REFRESH_TTL.getSeconds()));
        if (!Long.valueOf(1L).equals(rotated)) throw new BusinessException("刷新令牌已失效");
        redis.expire(sessionKey(sessionId), REFRESH_TTL);
        String access = JwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole(), sessionId, tokenVersion);
        return new RefreshTokenVO(access, newRefresh);
    }

    // 注销：解析令牌并删除对应会话，使后续携带该会话令牌的请求失效
    public void logout(String accessToken, String refreshToken) {
        String token = accessToken != null ? accessToken : refreshToken;
        if (token == null || !JwtUtils.validateToken(token)) return;
        removeSession(JwtUtils.getUserId(token), JwtUtils.getSessionId(token));
    }

    // 管理员强制下线：递增该用户会话版本号使全部已签发令牌失效，并清空其所有会话
    public void revokeAll(Long userId) {
        redis.opsForValue().increment(versionKey(userId));
        Set<String> sessions = redis.opsForSet().members(userSessionsKey(userId));
        if (sessions != null) {
            sessions.forEach(sessionId -> removeSession(userId, sessionId));
        }
        redis.delete(userSessionsKey(userId));
    }

    // 判断会话是否仍有效：Redis 中该 sessionId 归属是否仍是当前用户
    public boolean isSessionActive(Long userId, String sessionId) {
        return String.valueOf(userId).equals(redis.opsForValue().get(sessionKey(sessionId)));
    }

    // 删除单个会话：校验归属后清除会话键、刷新令牌键，并从用户会话集合中移除
    private void removeSession(Long userId, String sessionId) {
        if (sessionId == null) return;
        String owner = redis.opsForValue().get(sessionKey(sessionId));
        if (String.valueOf(userId).equals(owner)) {
            redis.delete(Set.of(sessionKey(sessionId), refreshKey(sessionId)));
            redis.opsForSet().remove(userSessionsKey(userId), sessionId);
        }
    }

    // 读取用户会话版本号，不存在则初始化为 0（版本号用于实现强制下线）
    private long currentVersion(Long userId) {
        String value = redis.opsForValue().get(versionKey(userId));
        if (value == null) {
            redis.opsForValue().setIfAbsent(versionKey(userId), "0");
            return 0L;
        }
        try { return Long.parseLong(value); } catch (Exception e) { return 0L; }
    }

    private String sessionKey(String id) { return "auth:session:" + id; }
    private String refreshKey(String id) { return "auth:refresh:" + id; }
    private String versionKey(Long id) { return "auth:user-version:" + id; }
    private String userSessionsKey(Long id) { return "auth:user-sessions:" + id; }
}
