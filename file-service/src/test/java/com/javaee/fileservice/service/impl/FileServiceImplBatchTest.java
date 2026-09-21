package com.javaee.fileservice.service.impl;

import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.security.BucketPermissionService;
import com.javaee.fileservice.security.FileUploadValidator;
import com.javaee.fileservice.security.MalwareScanner;
import com.javaee.fileservice.service.FileMetadataService;
import com.javaee.fileservice.service.ReliableFileUploader;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FileServiceImplBatchTest {

    @Mock private FileStorageConfig storageConfig;
    @Mock private FileMetadataService metadataService;
    @Mock private MinioClient minioClient;
    @Mock private BucketPermissionService bucketPermissionService;
    @Mock private FileUploadValidator validator;
    @Mock private MalwareScanner malwareScanner;
    @Mock private ReliableFileUploader reliableFileUploader;

    private FileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "fileStorageConfig", storageConfig);
        ReflectionTestUtils.setField(service, "fileMetadataService", metadataService);
        ReflectionTestUtils.setField(service, "minioClient", minioClient);
        ReflectionTestUtils.setField(service, "bucketPermissionService", bucketPermissionService);
        ReflectionTestUtils.setField(service, "fileUploadValidator", validator);
        ReflectionTestUtils.setField(service, "malwareScanner", malwareScanner);
        ReflectionTestUtils.setField(service, "reliableFileUploader", reliableFileUploader);
        org.mockito.Mockito.lenient().when(storageConfig.getStorageType()).thenReturn("local");
        org.mockito.Mockito.lenient().when(storageConfig.getMaxSize()).thenReturn(100L);
        org.mockito.Mockito.lenient().when(storageConfig.getMaxBatchFiles()).thenReturn(2);
        org.mockito.Mockito.lenient().when(storageConfig.getMaxBatchTotalSizeMb()).thenReturn(100L);
        org.mockito.Mockito.lenient().when(storageConfig.getMaxBatchConcurrency()).thenReturn(1);
        org.mockito.Mockito.lenient().when(storageConfig.getBucketName()).thenReturn("user-7");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", "test", List.of()));
    }

    @Test
    void rejectsBatchBeforeWritingWhenFileCountExceedsLimit() {
        MockMultipartFile[] files = {
                file("a.txt", "a"), file("b.txt", "b"), file("c.txt", "c")
        };

        assertThatThrownBy(() -> service.uploadMultiple(files))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件数量");

        verify(reliableFileUploader, never()).upload(any(MultipartFile.class), any(), any());
    }

    @Test
    void rejectsBatchBeforeWritingWhenTotalSizeExceedsLimit() {
        org.mockito.Mockito.when(storageConfig.getMaxBatchFiles()).thenReturn(10);
        org.mockito.Mockito.when(storageConfig.getMaxBatchTotalSizeMb()).thenReturn(1L);
        MockMultipartFile[] files = {
                new MockMultipartFile("file", "large.txt", "text/plain", new byte[1024 * 1024 + 1])
        };

        assertThatThrownBy(() -> service.uploadMultiple(files))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("总大小");

        verify(reliableFileUploader, never()).upload(any(MultipartFile.class), any(), any());
    }

    private MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }
}
