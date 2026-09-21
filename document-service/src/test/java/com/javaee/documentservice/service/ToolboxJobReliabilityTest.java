package com.javaee.documentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.client.ToolboxFileClient;
import com.javaee.documentservice.dto.ToolboxJobRequest;
import com.javaee.documentservice.entity.ToolboxJob;
import com.javaee.documentservice.mapper.ToolboxJobMapper;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolboxJobReliabilityTest {

    @Mock private DocumentService documents;
    @Mock private RabbitTemplate rabbit;
    @Mock private RedisTemplate<String, Object> redis;
    @Mock private ToolboxFileClient fileClient;
    @Mock private ToolboxJobMapper mapper;
    @Mock private ToolboxResultStore resultStore;

    @Test
    void redisOutageDoesNotPreventMysqlBackedSubmission() {
        ToolboxJobRequest request = new ToolboxJobRequest();
        request.setToolType("OCR");
        request.setDocumentIds(List.of("doc-1"));
        when(documents.getById("doc-1", 7L)).thenReturn(document());
        doThrow(new IllegalStateException("Redis unavailable")).when(redis).opsForValue();

        ToolboxJobService jobs = service();
        var snapshot = jobs.submit(request, 7L);

        assertThat(snapshot.get("status")).isEqualTo("PENDING");
        verify(mapper).insert(any(ToolboxJob.class));
        verify(rabbit).convertAndSend(eq("file.exchange"), eq("document.toolbox"), any(String.class));
    }

    @Test
    void taskCannotBeReadByAnotherUserEvenWhenJobIdIsKnown() {
        when(mapper.selectByUser("job-1", 8L)).thenReturn(null);

        assertThatThrownBy(() -> service().snapshot("job-1", 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void failedExpiryDeletionKeepsSuccessRowRetryable() {
        ToolboxJob job = job();
        when(mapper.selectExpired(any(), eq(50))).thenReturn(List.of(job));
        doThrow(new IllegalStateException("MinIO unavailable")).when(resultStore).delete(job);

        service().cleanupExpiredResults();

        verify(mapper, never()).markExpired(any(), any());
        assertThat(job.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void databaseSuccessTransitionFailureDeletesUploadedResultObject() {
        ToolboxJob job = pendingJob("job-1");
        when(mapper.claimPending(eq("job-1"), any())).thenReturn(1);
        when(mapper.selectById("job-1")).thenReturn(job);
        when(documents.getById("doc-1", 7L)).thenReturn(document());
        when(fileClient.download(any())).thenReturn("hello".getBytes(StandardCharsets.UTF_8));
        when(resultStore.put(eq(job), any(), eq("text/plain;charset=UTF-8")))
                .thenReturn("toolbox-results/org-1/7/job-1/ocr-result.txt");
        when(mapper.markSuccess(eq("job-1"), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        service().process("org-1|7|job-1");

        verify(resultStore).delete(eq(job));
        verify(mapper).markFailure(eq("job-1"), anyString());
    }

    private ToolboxJobService service() {
        return new ToolboxJobService(documents, rabbit, redis, fileClient, mapper, resultStore, new ObjectMapper());
    }

    private DocumentVO document() {
        DocumentVO document = new DocumentVO();
        document.setId("doc-1");
        document.setTitle("source.txt");
        document.setFileId("file-1");
        document.setUserId(7L);
        document.setOrganizationId("org-1");
        return document;
    }

    private ToolboxJob job() {
        ToolboxJob job = new ToolboxJob();
        job.setJobId("job-expired");
        job.setUserId(7L);
        job.setOrganizationId("org-1");
        job.setStatus("SUCCESS");
        job.setResultObjectKey("toolbox-results/org-1/7/job-expired/ocr-result.txt");
        return job;
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
}
