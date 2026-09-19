package com.javaee.aiservice.provider;

import com.javaee.aiservice.dto.UserAIProviderCredentialRequest;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.aiservice.vo.UserAIProviderCredentialVO;
import com.javaee.common.config.security.TenantContext;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestClient;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 用户自带模型密钥（BYOK）服务。
 *
 * <p>密钥使用服务端主密钥 AES-GCM 加密后写入 MySQL；数据库、接口响应、日志中都不保存明文。
 * 为避免把 AI 配置接口变成 SSRF 入口，仅允许已知的 HTTPS OpenAI 兼容域名。</p>
 */
@Service
public class UserAIProviderCredentialService {

    private static final String CIPHER_PREFIX = "v1";
    private static final String SYSTEM_PROMPT = "你是 SmartDoc 文档助手，请严格执行用户指令并直接返回结果。";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "api.deepseek.com",
            "api.openai.com",
            "dashscope.aliyuncs.com",
            "coding.dashscope.aliyuncs.com"
    );

    private final JdbcTemplate jdbc;
    private final RequestUserContext requestUserContext;

    @Value("${ai.credentials.master-key:}")
    private String masterKey;

    public UserAIProviderCredentialService(JdbcTemplate jdbc, RequestUserContext requestUserContext) {
        this.jdbc = jdbc;
        this.requestUserContext = requestUserContext;
    }

    public List<UserAIProviderCredentialVO> list() {
        String userId = requestUserContext.getRequiredUserId();
        String organizationId = organizationId();
        try {
            return jdbc.query(
                    "SELECT id, provider, model_name, base_url, api_key_hint, is_default, status, updated_at " +
                            "FROM ai_provider_credential WHERE user_id=? " + scopeSql() +
                            " ORDER BY is_default DESC, updated_at DESC",
                    (rs, rowNum) -> new UserAIProviderCredentialVO(
                            rs.getString("id"), rs.getString("provider"), rs.getString("model_name"),
                            rs.getString("base_url"), rs.getString("api_key_hint"),
                            rs.getBoolean("is_default"), rs.getString("status"),
                            String.valueOf(rs.getTimestamp("updated_at"))),
                    userId, organizationId, organizationId);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("模型配置数据表尚未初始化，请重启 AI 服务", ex);
        }
    }

    public UserAIProviderCredentialVO save(UserAIProviderCredentialRequest request) {
        ValidCredential credential = validate(request, true);
        String userId = requestUserContext.getRequiredUserId();
        String organizationId = organizationId();
        boolean makeDefault = Boolean.TRUE.equals(request.defaultCredential());

        List<String> existing = jdbc.query(
                "SELECT id FROM ai_provider_credential WHERE user_id=? AND provider=? AND model_name=? " +
                        scopeSql() + " LIMIT 1",
                (rs, rowNum) -> rs.getString("id"), userId, credential.provider(), credential.model(),
                organizationId, organizationId);

        if (makeDefault || existing.isEmpty()) {
            jdbc.update("UPDATE ai_provider_credential SET is_default=0 WHERE user_id=? " + scopeSql(),
                    userId, organizationId, organizationId);
            makeDefault = true;
        }

        String encrypted = encrypt(credential.apiKey());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO ai_provider_credential " +
                            "(id,user_id,organization_id,provider,model_name,base_url,api_key_ciphertext,api_key_hint,is_default,status) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE')",
                    UUID.randomUUID().toString(), userId, organizationId, credential.provider(), credential.model(),
                    credential.baseUrl(), encrypted, hint(credential.apiKey()), makeDefault);
        } else {
            jdbc.update("UPDATE ai_provider_credential SET base_url=?, api_key_ciphertext=?, api_key_hint=?, " +
                            "is_default=?, status='ACTIVE', updated_at=CURRENT_TIMESTAMP " +
                            "WHERE id=? AND user_id=? " + scopeSql(),
                    credential.baseUrl(), encrypted, hint(credential.apiKey()), makeDefault, existing.get(0), userId,
                    organizationId, organizationId);
        }
        return list().stream()
                .filter(item -> item.provider().equals(credential.provider()) && item.model().equals(credential.model()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("模型配置保存失败"));
    }

    public void delete(String id) {
        String userId = requestUserContext.getRequiredUserId();
        jdbc.update("DELETE FROM ai_provider_credential WHERE id=? AND user_id=? " + scopeSql(),
                id, userId, organizationId(), organizationId());
    }

    public UserAIProviderCredentialVO setDefault(String id) {
        String userId = requestUserContext.getRequiredUserId();
        String organizationId = organizationId();
        int updated = jdbc.update("UPDATE ai_provider_credential SET is_default=1, updated_at=CURRENT_TIMESTAMP " +
                        "WHERE id=? AND user_id=? " + scopeSql(), id, userId, organizationId, organizationId);
        if (updated == 0) throw new SecurityException("无权操作该模型配置");
        jdbc.update("UPDATE ai_provider_credential SET is_default=0 WHERE user_id=? AND id<>? " + scopeSql(),
                userId, id, organizationId, organizationId);
        return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    /** 测试临时配置，不会把 API Key 写入数据库。 */
    public Map<String, Object> test(UserAIProviderCredentialRequest request) {
        ValidCredential credential = validate(request, true);
        String result = call(credential, "请只回复 OK，不要添加其他内容。", false);
        return Map.of("success", true, "reply", result == null ? "" : result.trim());
    }

    /**
     * 如果当前用户配置了自己的默认模型，则返回用户模型响应；未配置时返回 empty，由系统模型兜底。
     */
    public Optional<String> callIfConfigured(String prompt, String requestedModel) {
        Optional<StoredCredential> stored = resolve(requestedModel);
        if (stored.isEmpty()) return Optional.empty();
        StoredCredential credential = stored.get();
        return Optional.of(call(new ValidCredential(credential.provider(), credential.model(), credential.baseUrl(),
                decrypt(credential.ciphertext())), prompt, true));
    }

    private Optional<StoredCredential> resolve(String requestedModel) {
        Optional<String> user = requestUserContext.getCurrentUserId();
        if (user.isEmpty()) return Optional.empty();
        String model = StringUtils.hasText(requestedModel) ? requestedModel.trim() : null;
        String organizationId = organizationId();
        try {
            List<StoredCredential> rows = jdbc.query(
                    "SELECT provider, model_name, base_url, api_key_ciphertext FROM ai_provider_credential " +
                            "WHERE user_id=? AND status='ACTIVE' " + scopeSql() +
                            " ORDER BY CASE WHEN ? IS NOT NULL AND model_name=? THEN 0 ELSE 1 END, " +
                            "is_default DESC, updated_at DESC LIMIT 1",
                    (rs, rowNum) -> new StoredCredential(rs.getString("provider"), rs.getString("model_name"),
                            rs.getString("base_url"), rs.getString("api_key_ciphertext")),
                    user.get(), organizationId, organizationId, model, model);
            return rows.stream().findFirst();
        } catch (DataAccessException ex) {
            // 兼容尚未执行新建表的旧实例，继续使用服务器默认模型。
            return Optional.empty();
        }
    }

    private ValidCredential validate(UserAIProviderCredentialRequest request, boolean requireApiKey) {
        if (request == null || !StringUtils.hasText(request.provider())) throw new IllegalArgumentException("请选择模型服务商");
        String provider = request.provider().trim().toLowerCase(Locale.ROOT);
        String model = StringUtils.hasText(request.model()) ? request.model().trim() : defaultModel(provider);
        String baseUrl = normalizeBaseUrl(provider, request.baseUrl());
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        if (requireApiKey && apiKey.isBlank()) throw new IllegalArgumentException("API Key 不能为空");
        if (apiKey.length() > 512) throw new IllegalArgumentException("API Key 长度不合法");
        return new ValidCredential(provider, model, baseUrl, apiKey);
    }

    private String normalizeBaseUrl(String provider, String requested) {
        String baseUrl = StringUtils.hasText(requested) ? requested.trim() : defaultBaseUrl(provider);
        URI uri;
        try { uri = URI.create(baseUrl); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("模型地址格式不正确"); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_HOSTS.contains(host) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("模型地址不在允许范围内，仅支持受信任的 HTTPS OpenAI 兼容服务");
        }
        if ("deepseek".equals(provider) && !"api.deepseek.com".equals(host)) {
            throw new IllegalArgumentException("DeepSeek 只支持 api.deepseek.com");
        }
        if ("dashscope".equals(provider) && !host.endsWith("dashscope.aliyuncs.com")) {
            throw new IllegalArgumentException("DashScope 地址不正确");
        }
        return baseUrl.replaceAll("/+$", "");
    }

    private String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "deepseek" -> "https://api.deepseek.com";
            case "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "openai" -> "https://api.openai.com/v1";
            default -> throw new IllegalArgumentException("暂不支持该模型服务商");
        };
    }

    private String defaultModel(String provider) {
        return switch (provider) {
            case "deepseek" -> "deepseek-chat";
            case "dashscope" -> "qwen-plus";
            case "openai" -> "gpt-4o-mini";
            default -> throw new IllegalArgumentException("暂不支持该模型服务商");
        };
    }

    private String call(ValidCredential credential, String prompt, boolean includeSystemPrompt) {
        try {
            OpenAiApi api = new OpenAiApi(credential.baseUrl(), credential.apiKey(), RestClient.builder(), WebClient.builder());
            ChatModel model = new OpenAiChatModel(api, OpenAiChatOptions.builder().withModel(credential.model()).build());
            List<org.springframework.ai.chat.messages.Message> messages = includeSystemPrompt
                    ? List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(prompt))
                    : List.of(new UserMessage(prompt));
            ChatResponse response = model.call(new Prompt(messages));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new IllegalStateException("模型未返回有效结果");
            }
            return response.getResult().getOutput().getContent();
        } catch (Exception ex) {
            throw new IllegalStateException("模型连接失败，请检查 API Key、模型名称和服务商额度", ex);
        }
    }

    private String encrypt(String plaintext) {
        SecretKeySpec key = secretKey();
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return CIPHER_PREFIX + ":" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) { throw new IllegalStateException("模型密钥加密失败", ex); }
    }

    private String decrypt(String encoded) {
        try {
            String[] parts = encoded.split(":", 3);
            if (parts.length != 3 || !CIPHER_PREFIX.equals(parts[0])) throw new IllegalStateException("模型密钥格式不受支持");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("模型密钥解密失败，请重新配置 API Key", ex); }
    }

    private SecretKeySpec secretKey() {
        if (!StringUtils.hasText(masterKey) || masterKey.length() < 16) {
            throw new IllegalStateException("未配置 SMARTDOC_AI_CREDENTIAL_MASTER_KEY，无法安全保存模型密钥");
        }
        try { return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8)), "AES"); }
        catch (Exception ex) { throw new IllegalStateException("模型密钥初始化失败", ex); }
    }

    private String hint(String apiKey) { return apiKey.length() <= 4 ? "****" : "****" + apiKey.substring(apiKey.length() - 4); }
    private String organizationId() { return TenantContext.get(); }
    private String scopeSql() { return " AND ((organization_id=?) OR (organization_id IS NULL AND ? IS NULL))"; }

    private record ValidCredential(String provider, String model, String baseUrl, String apiKey) { }
    private record StoredCredential(String provider, String model, String baseUrl, String ciphertext) { }
}
