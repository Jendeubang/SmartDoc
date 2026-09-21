package com.javaee.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/** Sends reset mail through a private mail-relay webhook so SMTP credentials stay outside the app. */
@Service
public class PasswordResetMailService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailService.class);
    private final RestTemplate rest = new RestTemplate();

    @Value("${security.password-reset.frontend-url:http://localhost:5173/reset-password}") private String frontendUrl;
    @Value("${security.password-reset.delivery-url:}") private String deliveryUrl;
    @Value("${security.password-reset.delivery-token:}") private String deliveryToken;

    public void send(String email, String token) {
        if (deliveryUrl == null || deliveryUrl.isBlank() || email == null || email.isBlank()) {
            log.warn("Password-reset delivery webhook is not configured; no token was disclosed for account safety");
            return;
        }
        String link = UriComponentsBuilder.fromUriString(frontendUrl).queryParam("token", token).build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (deliveryToken != null && !deliveryToken.isBlank()) headers.setBearerAuth(deliveryToken);
        Map<String, String> payload = Map.of(
                "to", email,
                "subject", "SmartDoc 密码重置",
                "text", "此重置链接 15 分钟内有效且只能使用一次：\n" + link);
        rest.postForEntity(deliveryUrl, new HttpEntity<>(payload, headers), Void.class);
    }
}
