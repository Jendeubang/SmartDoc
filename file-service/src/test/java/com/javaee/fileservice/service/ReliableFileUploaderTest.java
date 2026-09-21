package com.javaee.fileservice.service;

import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.security.FileUploadValidator;
import com.javaee.fileservice.security.MalwareScanner;
import com.javaee.fileservice.storage.FileObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReliableFileUploaderTest {

    @Mock
    private FileStorageConfig storageConfig;

    @Mock
    private FileMetadataService metadataService;

    @Mock
    private FileUploadValidator validator;

    @Mock
    private MalwareScanner malwareScanner;

    @Mock
    private FileObjectStore objectStore;

    private final List<String> savedStatuses = new ArrayList<>();

    private ReliableFileUploader uploader;

    @BeforeEach
    void setUp() {
        when(storageConfig.getStorageType()).thenReturn("minio");
        when(storageConfig.getBucketName()).thenReturn("user-7");
        when(validator.validateFileName(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().doAnswer(invocation -> {
            savedStatuses.add(invocation.getArgument(0, FileMetadata.class).getStatus());
            return null;
        }).when(metadataService).saveMetadata(any());
        uploader = new ReliableFileUploader(storageConfig, metadataService, validator, malwareScanner, objectStore);
    }

    @Test
    void writesTempMetadataPromotesAndMarksReady() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        ReliableFileUploader.UploadResult result = uploader.uploadBytes(
                "guide.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8), "7", "org-1");

        assertThat(result.fileName()).isEqualTo("guide.txt");
        assertThat(result.bytes()).containsExactly("hello".getBytes(StandardCharsets.UTF_8));
        verify(objectStore).put(any(String.class), eq(result.bytes()), eq("text/plain"));
        verify(objectStore).promote(any(String.class), any(String.class));

        verify(metadataService).saveMetadata(any(FileMetadata.class));
        assertThat(savedStatuses).containsExactly("PENDING");

        ArgumentCaptor<FileMetadata> updated = ArgumentCaptor.forClass(FileMetadata.class);
        verify(metadataService).updateMetadata(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo("READY");
        assertThat(updated.getValue().getObjectKey()).isNotBlank();
        assertThat(updated.getValue().getObjectKey()).doesNotStartWith(".uploading/");
    }

    @Test
    void multipartUploadUsesRepeatableStreamAndDoesNotRetainWholeBody() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        ReliableFileUploader.UploadResult result = uploader.upload(file, "7", "org-1");

        assertThat(result.bytes()).isNull();
        verify(objectStore).put(any(String.class), any(InputStream.class), anyLong(), eq("text/plain"));
    }

    @Test
    void objectUploadFailureDoesNotCreateMetadata() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(objectStore).put(any(String.class), any(InputStream.class), anyLong(), any(String.class));

        assertThatThrownBy(() -> uploader.upload(file, "7", "org-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("文件上传失败");

        verify(metadataService, never()).saveMetadata(any());
        verify(metadataService, never()).updateMetadata(any());
        verify(objectStore).delete(any(String.class));
    }

    @Test
    void promoteFailureDeletesTempAndMarksMetadataFailed() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        doThrow(new IllegalStateException("promote failed"))
                .when(objectStore).promote(any(), any());

        assertThatThrownBy(() -> uploader.upload(file, "7", "org-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("文件上传失败");

        ArgumentCaptor<FileMetadata> updated = ArgumentCaptor.forClass(FileMetadata.class);
        verify(metadataService).updateMetadata(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo("FAILED");
        verify(objectStore).delete(any());
    }

    @Test
    void sameTenantDuplicateIsReplacedOnlyAfterNewFileIsReady() throws Exception {
        FileMetadata old = new FileMetadata();
        old.setFileId("old-file");
        old.setObjectKey("old-file.txt");
        old.setBucketName("user-7");
        old.setStatus("READY");
        when(metadataService.findReadyByMd5(any(), eq("7"), eq("org-1"))).thenReturn(old);

        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        uploader.upload(file, "7", "org-1");

        verify(objectStore).promote(any(), any());
        verify(objectStore).delete("old-file.txt");
        verify(metadataService).deleteMetadata("old-file");
    }

    @Test
    void sameMd5InAnotherOrganizationDoesNotDeleteExistingFile() throws Exception {
        FileMetadata old = new FileMetadata();
        old.setFileId("other-org-file");
        old.setObjectKey("other-org-file.txt");
        old.setBucketName("user-7");
        old.setOrganizationId("org-2");
        old.setStatus("READY");
        when(metadataService.findReadyByMd5(any(), eq("7"), eq("org-1"))).thenReturn(null);

        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        uploader.upload(file, "7", "org-1");

        verify(objectStore, never()).delete("other-org-file.txt");
        verify(metadataService, never()).deleteMetadata("other-org-file");
    }
}
