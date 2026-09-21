package com.javaee.fileservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileMetadataServiceImplTest {

    @Mock
    private FileMetadataMapper mapper;

    @Mock
    private FileStorageConfig storage;

    private FileMetadataServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileMetadataServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fileMetadataMapper", mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fileStorageConfig", storage);
    }

    @Test
    void maliciousSortNeverReachesSqlSegmentAndPaginationIsBounded() {
        when(storage.getStorageType()).thenReturn("local");
        when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.getFileList(-1, 10_000, "id desc; drop table file_metadata", "asc");

        ArgumentCaptor<Page<FileMetadata>> page = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Wrapper<FileMetadata>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(page.capture(), wrapper.capture());
        assertThat(page.getValue().getCurrent()).isEqualTo(1);
        assertThat(page.getValue().getSize()).isEqualTo(100);
        assertThat(wrapper.getValue().getSqlSegment()).contains("create_time");
        assertThat(wrapper.getValue().getSqlSegment()).doesNotContainIgnoringCase("drop table");
        assertThat(wrapper.getValue().getSqlSegment()).contains("status");
    }

    @Test
    void findsOnlyTheLatestReadyDuplicateWithinTheCurrentTenant() {
        FileMetadata existing = new FileMetadata();
        existing.setFileId("old-file");
        existing.setStatus("READY");
        existing.setBucketName("user-7");
        when(storage.getStorageType()).thenReturn("minio");
        when(storage.getBucketName()).thenReturn("user-7");
        when(mapper.findReadyByMd5(eq("digest"), eq("7"), eq("org-1"), eq("user-7")))
                .thenReturn(existing);

        assertThat(service.findReadyByMd5("digest", "7", "org-1")).isSameAs(existing);
        verify(mapper).findReadyByMd5("digest", "7", "org-1", "user-7");
    }
}
