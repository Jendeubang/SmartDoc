package com.javaee.documentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.documentservice.client.ToolboxFileClient;
import com.javaee.documentservice.dto.ToolboxJobRequest;
import com.javaee.documentservice.entity.ToolboxJob;
import com.javaee.documentservice.mapper.ToolboxJobMapper;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolboxJobServiceTest {

    @Mock
    private DocumentService documents;
    @Mock
    private RabbitTemplate rabbit;
    @Mock
    private RedisTemplate<String, Object> redis;
    @Mock
    private ValueOperations<String, Object> values;
    @Mock
    private ZSetOperations<String, Object> zsets;
    @Mock
    private ToolboxFileClient fileClient;
    @Mock
    private ToolboxJobMapper mapper;
    @Mock
    private ToolboxResultStore resultStore;

    private ToolboxJobService jobs;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        lenient().when(redis.opsForZSet()).thenReturn(zsets);
        jobs = new ToolboxJobService(documents, rabbit, redis, fileClient, mapper, resultStore, new ObjectMapper());
    }

    @Test
    void submitPersistsPendingJobInMysqlAndPublishesContextOnly() {
        ToolboxJobRequest request = new ToolboxJobRequest();
        request.setToolType("OCR");
        request.setDocumentIds(List.of("doc-1"));
        when(documents.getById("doc-1", 7L)).thenReturn(document("doc-1", 7L));
        jobs.submit(request, 7L);

        ArgumentCaptor<ToolboxJob> capturedJob = ArgumentCaptor.forClass(ToolboxJob.class);
        verify(mapper).insert(capturedJob.capture());
        assertThat(capturedJob.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(capturedJob.getValue().getOrganizationId()).isEqualTo("org-1");
        assertThat(capturedJob.getValue().getDocumentIdsJson()).contains("doc-1");

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq("file.exchange"), eq("document.toolbox"), message.capture());
        assertThat(String.valueOf(message.getValue()))
                .doesNotContain("Authorization")
                .doesNotContain("Bearer ");
    }

    @Test
    void successfulProcessingStoresResultInMinioAndNotRedis() {
        ToolboxJob job = pendingJob("job-1");
        when(mapper.claimPending(eq("job-1"), any())).thenReturn(1);
        when(mapper.selectById("job-1")).thenReturn(job);
        when(documents.getById("doc-1", 7L)).thenReturn(document("doc-1", 7L));
        when(fileClient.download(any())).thenReturn("hello".getBytes(StandardCharsets.UTF_8));
        when(resultStore.put(eq(job), any(), eq("text/plain;charset=UTF-8"))).thenReturn("toolbox-results/org-1/7/job-1/ocr-result.txt");
        when(mapper.markSuccess(eq("job-1"), anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        jobs.process("org-1|7|job-1");

        verify(resultStore).put(eq(job), any(), eq("text/plain;charset=UTF-8"));
        verify(mapper).markSuccess(eq("job-1"), eq("toolbox-results/org-1/7/job-1/ocr-result.txt"),
                eq("ocr-result.txt"), eq("text/plain;charset=UTF-8"), anyString());
        ArgumentCaptor<Object> cacheValues = ArgumentCaptor.forClass(Object.class);
        verify(values, org.mockito.Mockito.atLeastOnce()).set(anyString(), cacheValues.capture(), anyLong(), any());
        assertThat(cacheValues.getAllValues()).noneMatch(byte[].class::isInstance);
    }

    @Test
    void duplicateDeliveryDoesNotDownloadOrProcessSourceAgain() {
        when(mapper.claimPending(eq("job-1"), any())).thenReturn(0);

        jobs.process("org-1|7|job-1");

        verify(fileClient, never()).download(any());
        verify(resultStore, never()).put(any(), any(), anyString());
    }

    @Test
    void retryUsesConditionalMysqlTransitionAndRepublishes() {
        ToolboxJob failed = pendingJob("job-1");
        failed.setStatus("FAILED");
        when(mapper.selectByUser("job-1", 7L)).thenReturn(failed);
        when(mapper.retryFailed("job-1", 7L, "org-1")).thenReturn(1);

        Map<String, Object> snapshot = jobs.retry("job-1", 7L);

        assertThat(snapshot.get("status")).isEqualTo("PENDING");
        verify(resultStore).delete(failed);
        verify(mapper).retryFailed("job-1", 7L, "org-1");
        verify(rabbit).convertAndSend(eq("file.exchange"), eq("document.toolbox"), anyString());
    }

    @Test
    void outputReadsOnlyFromMinioAfterOwnershipAndStatusCheck() {
        ToolboxJob success = pendingJob("job-1");
        success.setStatus("SUCCESS");
        success.setResultObjectKey("toolbox-results/org-1/7/job-1/ocr-result.txt");
        success.setContentType("text/plain;charset=UTF-8");
        when(mapper.selectByUser("job-1", 7L)).thenReturn(success);
        when(resultStore.get(success)).thenReturn("result".getBytes(StandardCharsets.UTF_8));

        assertThat(jobs.output("job-1", 7L)).containsExactly("result".getBytes(StandardCharsets.UTF_8));
        verify(resultStore).get(success);
    }

    private ToolboxJob pendingJob(String id) {
        ToolboxJob job = new ToolboxJob();
        job.setJobId(id);
        job.setUserId(7L);
        job.setOrganizationId("org-1");
        job.setToolType("OCR");
        job.setDocumentIdsJson("[\"doc-1\"]");
        job.setDocumentNamesJson("[\"source.txt\"]");
        job.setPages("");
        job.setStatus("PENDING");
        job.setProgress(5);
        job.setRetryCount(0);
        return job;
    }

    private DocumentVO document(String id, Long userId) {
        DocumentVO document = new DocumentVO();
        document.setId(id);
        document.setTitle("source.txt");
        document.setFileId("file-1");
        document.setUserId(userId);
        document.setOrganizationId("org-1");
        return document;
    }
}
