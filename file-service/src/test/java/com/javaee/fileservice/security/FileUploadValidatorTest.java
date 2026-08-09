package com.javaee.fileservice.security;

import com.javaee.fileservice.config.FileStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUploadValidatorTest {

    private FileStorageConfig storageConfig;
    private FileUploadValidator validator;

    @BeforeEach
    void setUp() {
        storageConfig = new FileStorageConfig();
        ReflectionTestUtils.setField(storageConfig, "maxSize", 100L);
        validator = new FileUploadValidator(storageConfig);
    }

    @Test
    void acceptsPdfWhenExtensionAndSignatureMatch() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.7 sample".getBytes(StandardCharsets.US_ASCII));

        assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void rejectsSpoofedPdfContent() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扩展名不匹配");
    }

    @Test
    void rejectsExecutableDoubleExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf.exe", "application/octet-stream", new byte[]{0x4D, 0x5A});

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("可执行");
    }

    @Test
    void enforcesConfiguredMaximumSize() {
        ReflectionTestUtils.setField(storageConfig, "maxSize", 0L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "a".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上传限制");
    }
}