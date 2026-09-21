package com.javaee.common.config.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InternalServiceTokenProviderTest {
    @Test
    void readsInternalTokenFromDockerSecretFile(@TempDir Path tempDir) throws Exception {
        Path secret = tempDir.resolve("internal-service-token");
        Files.writeString(secret, "  file-backed-service-secret\n", StandardCharsets.UTF_8);
        InternalServiceTokenProvider provider = new InternalServiceTokenProvider();
        ReflectionTestUtils.setField(provider, "value", "");
        ReflectionTestUtils.setField(provider, "file", secret.toString());

        assertThat(provider.getRequiredToken()).isEqualTo("file-backed-service-secret");
    }

    @Test
    void refusesToOperateWithoutConfiguredSecret() {
        InternalServiceTokenProvider provider = new InternalServiceTokenProvider();
        ReflectionTestUtils.setField(provider, "value", "");
        ReflectionTestUtils.setField(provider, "file", "");

        org.assertj.core.api.Assertions.assertThatThrownBy(provider::getRequiredToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }
}
