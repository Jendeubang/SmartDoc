package com.javaee.common.utils;

import com.javaee.common.constant.CommonConstant;
import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.exception.TokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

// 【简历第6条 · JWT 密钥轮换】JWT 工具类：JWT_KEYS 保存 kid:secret 多密钥键值对，
// 签发时把 kid 写入 JWT 头并用激活密钥（JWT_ACTIVE_KID 指定，缺省取第一个）签名；
// 验签时按 kid 从密钥环选择对应密钥，使旧密钥在轮换期间仍可验签，实现平滑轮换。
/**
 * Shared JWT helper. Keys are supplied through JWT_KEYS (kid:secret pairs) so
 * every service validates the same key ring and old keys can remain available
 * during rotation. The first key is active unless JWT_ACTIVE_KID is set.
 */
public final class JwtUtils {
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final String CLAIM_TYPE = "tokenType";
    private static final String CLAIM_SESSION_ID = "sid";
    private static final String CLAIM_SESSION_VERSION = "sessionVersion";
    private static final long ACCESS_EXPIRATION = Duration.ofMinutes(30).toMillis();
    private static final long REFRESH_EXPIRATION = Duration.ofDays(7).toMillis();

    private JwtUtils() {}

    // 签发访问令牌（不带角色/会话信息的便捷重载，默认新建会话、版本号 0）
    public static String generateToken(Long userId, String username) {
        return generateToken(userId, username, null, UUID.randomUUID().toString(), 0L);
    }

    // 签发访问令牌（携带角色）
    public static String generateToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, UUID.randomUUID().toString(), 0L);
    }

    // 签发访问令牌：写入用户名、角色声明，有效期 30 分钟
    public static String generateToken(Long userId, String username, String role,
                                       String sessionId, long sessionVersion) {
        Map<String, Object> claims = baseClaims(userId, sessionId, sessionVersion, TOKEN_TYPE_ACCESS);
        claims.put(CommonConstant.TOKEN_CLAIM_USERNAME, username);
        if (role != null) claims.put(CommonConstant.TOKEN_CLAIM_ROLE, role);
        return generateToken(claims, ACCESS_EXPIRATION);
    }

    // 签发刷新令牌（默认新建会话）
    public static String generateRefreshToken(Long userId) {
        return generateRefreshToken(userId, UUID.randomUUID().toString(), 0L);
    }

    // 签发刷新令牌：与指定会话绑定，有效期 7 天
    public static String generateRefreshToken(Long userId, String sessionId, long sessionVersion) {
        return generateToken(baseClaims(userId, sessionId, sessionVersion, TOKEN_TYPE_REFRESH), REFRESH_EXPIRATION);
    }

    // 构造令牌基础声明：用户ID、令牌类型、会话ID、会话版本号、唯一令牌ID(jti)
    private static Map<String, Object> baseClaims(Long userId, String sessionId,
                                                   long sessionVersion, String tokenType) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(CommonConstant.TOKEN_CLAIM_USER_ID, userId);
        claims.put(CLAIM_TYPE, tokenType);
        claims.put(CLAIM_SESSION_ID, sessionId);
        claims.put(CLAIM_SESSION_VERSION, sessionVersion);
        claims.put("jti", UUID.randomUUID().toString());
        return claims;
    }

    // 用激活密钥签名，并把 kid 写入 JWT 头——验签时据此选密钥，是密钥轮换的核心
    private static String generateToken(Map<String, Object> claims, long expiration) {
        KeyRing ring = KeyRing.load();
        Date now = new Date();
        return Jwts.builder()
                .setHeaderParam("kid", ring.activeKid())
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expiration))
                .signWith(ring.activeKey())
                .compact();
    }

    // 解析并验签令牌：按 JWT 头中的 kid 从密钥环选取对应密钥，签名/过期不合法抛 TokenException
    public static Claims parseToken(String token) {
        try {
            KeyRing ring = KeyRing.load();
            return Jwts.parserBuilder()
                    .setSigningKeyResolver(new SigningKeyResolverAdapter() {
                        @Override
                        public java.security.Key resolveSigningKey(JwsHeader header, Claims claims) {
                            String kid = header.getKeyId();
                            SecretKey key = ring.keys().get(kid);
                            if (key == null) throw new JwtException("Unknown JWT key id");
                            return key;
                        }
                    })
                    .build().parseClaimsJws(token).getBody();
        } catch (Exception e) {
            throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
        }
    }

    // 校验令牌签名与有效期是否合法
    public static boolean validateToken(String token) {
        try { parseToken(token); return true; } catch (Exception e) { return false; }
    }

    // 校验令牌是否为访问令牌（防止用刷新令牌冒充访问令牌）
    public static boolean validateAccessToken(String token) {
        return validateType(token, TOKEN_TYPE_ACCESS);
    }

    // 校验令牌是否为刷新令牌
    public static boolean validateRefreshToken(String token) {
        return validateType(token, TOKEN_TYPE_REFRESH);
    }

    // 校验令牌类型声明是否与预期一致
    private static boolean validateType(String token, String expected) {
        try { return expected.equals(parseToken(token).get(CLAIM_TYPE, String.class)); }
        catch (Exception e) { return false; }
    }

    public static Long getUserId(String token) {
        return parseToken(token).get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class);
    }

    public static String getUsername(String token) {
        return parseToken(token).get(CommonConstant.TOKEN_CLAIM_USERNAME, String.class);
    }

    public static String getRole(String token) {
        return parseToken(token).get(CommonConstant.TOKEN_CLAIM_ROLE, String.class);
    }

    public static String getSessionId(String token) { return parseToken(token).get(CLAIM_SESSION_ID, String.class); }

    public static long getSessionVersion(String token) {
        Number value = parseToken(token).get(CLAIM_SESSION_VERSION, Number.class);
        return value == null ? 0L : value.longValue();
    }

    public static String getTokenId(String token) { return parseToken(token).getId(); }

    public static long getRemainingTtlMillis(String token) {
        return Math.max(0L, parseToken(token).getExpiration().getTime() - System.currentTimeMillis());
    }

    public static boolean isTokenExpired(String token) {
        try { return parseToken(token).getExpiration().before(new Date()); }
        catch (Exception e) { return true; }
    }

    // 从 Authorization 头中提取 Bearer 前缀后的令牌字符串
    public static String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(CommonConstant.TOKEN_PREFIX)) {
            return authHeader.substring(CommonConstant.TOKEN_PREFIX.length());
        }
        throw new TokenException(ErrorCodeEnum.TOKEN_ERROR);
    }

    // 计算 SHA-256 哈希，用于在 Redis 中存储刷新令牌指纹而非明文
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash token", e);
        }
    }

    // 密钥环：保存 kid -> 密钥 的映射及当前激活密钥，供签名与验签统一使用
    private record KeyRing(String activeKid, Map<String, SecretKey> keys) {
        SecretKey activeKey() { return keys.get(activeKid); }

        // 从系统属性/环境变量/密钥文件读取 JWT_KEYS（kid:secret 键值对），解析密钥环并确定激活密钥
        static KeyRing load() {
            String configured = firstNonBlank(System.getProperty("smartdoc.jwt.keys"), System.getenv("JWT_KEYS"));
            if (configured == null) configured = readSecretFile(System.getenv("JWT_KEYS_FILE"));
            if (configured == null) {
                throw new IllegalStateException("JWT_KEYS is required (format: current:at-least-32-byte-secret,previous:old-secret)");
            }
            Map<String, SecretKey> keys = new LinkedHashMap<>();
            for (String entry : configured.split(",")) {
                int separator = entry.indexOf(':');
                if (separator < 1) throw new IllegalStateException("Invalid JWT_KEYS entry");
                String kid = entry.substring(0, separator).trim();
                String secret = entry.substring(separator + 1).trim();
                if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                    throw new IllegalStateException("JWT secret for " + kid + " must contain at least 32 bytes");
                }
                keys.put(kid, Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)));
            }
            String requested = firstNonBlank(System.getProperty("smartdoc.jwt.active-kid"), System.getenv("JWT_ACTIVE_KID"));
            String active = requested == null ? keys.keySet().iterator().next() : requested;
            if (!keys.containsKey(active)) throw new IllegalStateException("JWT_ACTIVE_KID is not present in JWT_KEYS");
            return new KeyRing(active, Map.copyOf(keys));
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) if (value != null && !value.isBlank()) return value.trim();
            return null;
        }

        private static String readSecretFile(String file) {
            if (file == null || file.isBlank()) return null;
            try { return Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8).trim(); }
            catch (Exception e) { throw new IllegalStateException("Unable to read JWT_KEYS_FILE", e); }
        }
    }
}
