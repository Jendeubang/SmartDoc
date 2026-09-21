package com.javaee.fileservice.service.impl;

import com.javaee.fileservice.client.DocumentServiceClient;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.security.BucketPermissionService;
import com.javaee.fileservice.security.FileUploadValidator;
import com.javaee.fileservice.security.MalwareScanner;
import com.javaee.fileservice.service.FileMetadataService;
import com.javaee.fileservice.service.ReliableFileUploader;
import com.javaee.fileservice.storage.FileObjectStore;
import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class FileServiceImplChunkTest {

    @Mock
    private FileStorageConfig storageConfig;
    @Mock
    private FileMetadataService metadataService;
    @Mock
    private MinioClient minioClient;
    @Mock
    private BucketPermissionService bucketPermissionService;
    @Mock
    private FileUploadValidator validator;
    @Mock
    private MalwareScanner malwareScanner;
    @Mock
    private ReliableFileUploader reliableFileUploader;
    @Mock
    private FileObjectStore fileObjectStore;
    @Mock
    private DocumentServiceClient documentServiceClient;

    private FileServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "fileStorageConfig", storageConfig);
        ReflectionTestUtils.setField(service, "fileMetadataService", metadataService);
        ReflectionTestUtils.setField(service, "minioClient", minioClient);
        ReflectionTestUtils.setField(service, "bucketPermissionService", bucketPermissionService);
        ReflectionTestUtils.setField(service, "fileUploadValidator", validator);
        ReflectionTestUtils.setField(service, "malwareScanner", malwareScanner);
        ReflectionTestUtils.setField(service, "reliableFileUploader", reliableFileUploader);
        ReflectionTestUtils.setField(service, "fileObjectStore", fileObjectStore);
        ReflectionTestUtils.setField(service, "documentServiceClient", documentServiceClient);
        lenient().when(storageConfig.getStorageType()).thenReturn("local");
        lenient().when(storageConfig.getMaxSize()).thenReturn(100L);
        lenient().when(storageConfig.getBucketName()).thenReturn("user-7");
        lenient().when(validator.validateFileName(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", "test", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsInvalidChunkRangeBeforeWriting() {
        MockMultipartFile chunk = chunk("a");

        assertThatThrownBy(() -> service.uploadChunk(chunk, "upload-1", 2, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
    }

    @Test
    void rejectsMissingChunkAndDoesNotCallReliableUploader() {
        service.uploadChunk(chunk("a"), "upload-2", 0, 2);

        assertThatThrownBy(() -> service.mergeChunk("upload-2", "note.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("分片合并失败");

        verify(reliableFileUploader, never()).uploadBytes(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsChangingTotalChunksAndDuplicateIndex() {
        service.uploadChunk(chunk("a"), "upload-3", 0, 2);

        assertThatThrownBy(() -> service.uploadChunk(chunk("b"), "upload-3", 0, 3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("总数");
        assertThatThrownBy(() -> service.uploadChunk(chunk("b"), "upload-3", 0, 2))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void mergesAllChunksThroughFileBackedUploader() throws Exception {
        service.uploadChunk(chunk("a"), "upload-4", 0, 2);
        service.uploadChunk(chunk("b"), "upload-4", 1, 2);
        doReturn(new ReliableFileUploader.UploadResult(
                "merged-file", "note.txt", "text/plain", null, "md5", "merged-file.txt"))
                .when(reliableFileUploader).uploadFile(any(Path.class), any(), any(), any(), any());

        service.mergeChunk("upload-4", "note.txt");

        verify(reliableFileUploader).uploadFile(any(Path.class), any(), any(), any(), any());
    }

    @Test
    void mergesChunksThroughFileBackedUploadInsteadOfReadingAllBytes() throws Exception {
        service.uploadChunk(chunk("a"), "upload-5", 0, 2);
        service.uploadChunk(chunk("b"), "upload-5", 1, 2);
        doReturn(new ReliableFileUploader.UploadResult(
                "merged-file", "note.txt", "text/plain", null, "md5", "merged-file.txt"))
                .when(reliableFileUploader).uploadFile(any(Path.class), any(), any(), any(), any());

        service.mergeChunk("upload-5", "note.txt");

        verify(reliableFileUploader).uploadFile(any(Path.class), any(), any(), any(), any());
    }

    private MockMultipartFile chunk(String content) {
        return new MockMultipartFile("chunk", "chunk.bin", "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
