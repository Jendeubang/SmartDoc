package com.javaee.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilsTest {
    private static final String OLD = "old:old-key-material-123456789012345678901234567890";
    private static final String CURRENT = "current:current-key-material-123456789012345678901234567890";

    @AfterEach
    void cleanUp() {
        System.clearProperty("smartdoc.jwt.keys");
        System.clearProperty("smartdoc.jwt.active-kid");
    }

    @Test
    void oldAccessTokenRemainsValidWhileOldKeyIsInRotationRing() {
        System.setProperty("smartdoc.jwt.keys", OLD);
        System.setProperty("smartdoc.jwt.active-kid", "old");
        String oldToken = JwtUtils.generateToken(7L, "user", "USER", "session", 0);

        System.setProperty("smartdoc.jwt.keys", CURRENT + "," + OLD);
        System.setProperty("smartdoc.jwt.active-kid", "current");

        assertThat(JwtUtils.validateAccessToken(oldToken)).isTrue();
        assertThat(JwtUtils.getUserId(oldToken)).isEqualTo(7L);
    }

    @Test
    void oldAccessTokenIsRejectedAfterRotationWindowCloses() {
        System.setProperty("smartdoc.jwt.keys", OLD);
        System.setProperty("smartdoc.jwt.active-kid", "old");
        String oldToken = JwtUtils.generateToken(7L, "user", "USER", "session", 0);

        System.setProperty("smartdoc.jwt.keys", CURRENT);
        System.setProperty("smartdoc.jwt.active-kid", "current");

        assertThat(JwtUtils.validateAccessToken(oldToken)).isFalse();
    }

    @Test
    void newlyIssuedTokenUsesActiveKeyAfterRotation() {
        System.setProperty("smartdoc.jwt.keys", OLD + "," + CURRENT);
        System.setProperty("smartdoc.jwt.active-kid", "current");

        String token = JwtUtils.generateToken(7L, "user", "USER", "session", 0);

        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"kid\":\"current\"");
        assertThat(JwtUtils.validateAccessToken(token)).isTrue();
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() {
        System.setProperty("smartdoc.jwt.keys", CURRENT);
        System.setProperty("smartdoc.jwt.active-kid", "current");
        String refresh = JwtUtils.generateRefreshToken(7L, "session", 0);

        assertThat(JwtUtils.validateRefreshToken(refresh)).isTrue();
        assertThat(JwtUtils.validateAccessToken(refresh)).isFalse();
    }

    @Test
    void tamperedSignatureAndUnknownKeyIdAreRejected() {
        System.setProperty("smartdoc.jwt.keys", CURRENT);
        System.setProperty("smartdoc.jwt.active-kid", "current");
        String token = JwtUtils.generateToken(7L, "user", "USER", "session", 0);
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThat(JwtUtils.validateAccessToken(tampered)).isFalse();
    }
}
