package com.javaee.documentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.documentservice.client.ToolboxFileClient;
import com.javaee.documentservice.entity.ToolboxJob;
import com.javaee.documentservice.mapper.ToolboxJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolboxJobRecoveryTest {

    @Mock private DocumentService documents;
    @Mock private RabbitTemplate rabbit;
    @Mock private RedisTemplate<String, Object> redis;
    @Mock private ToolboxFileClient fileClient;
    @Mock private ToolboxJobMapper mapper;
    @Mock private ToolboxResultStore resultStore;

    private ToolboxJobService jobs;

    @BeforeEach
    void setUp() {
        jobs = new ToolboxJobService(documents, rabbit, redis, fileClient, mapper, resultStore, new ObjectMapper());
    }

    @Test
    void staleProcessingJobsAreResetAndRepublished() {
        ToolboxJob job = job("job-stale", "PROCESSING");
        when(mapper.resetAllStaleProcessing(any())).thenReturn(1);
        when(mapper.selectDispatchable(any(), anyInt())).thenReturn(List.of(job));
        when(mapper.markDispatched("job-stale")).thenReturn(1);

        jobs.recoverPendingJobs();

        verify(mapper).resetAllStaleProcessing(any());
        verify(mapper).markDispatched("job-stale");
        verify(rabbit).convertAndSend(eq("file.exchange"), eq("document.toolbox"), eq("org-1|7|job-stale"));
    }

    @Test
    void oldPendingJobsAreRepublishedAfterBrokerInterruptionWindow() {
        ToolboxJob job = job("job-pending", "PENDING");
        when(mapper.resetAllStaleProcessing(any())).thenReturn(0);
        when(mapper.selectDispatchable(any(), anyInt())).thenReturn(List.of(job));
        when(mapper.markDispatched("job-pending")).thenReturn(1);

        jobs.recoverPendingJobs();

        verify(mapper).markDispatched("job-pending");
        verify(rabbit).convertAndSend(eq("file.exchange"), eq("document.toolbox"), eq("org-1|7|job-pending"));
    }

    @Test
    void expiredResultIsDeletedBeforeJobIsMarkedExpired() {
        ToolboxJob job = job("job-expired", "SUCCESS");
        job.setResultObjectKey("toolbox-results/org-1/7/job-expired/ocr-result.txt");
        when(mapper.selectExpired(any(), anyInt())).thenReturn(List.of(job));
        when(mapper.markExpired(eq("job-expired"), any())).thenReturn(1);

        jobs.cleanupExpiredResults();

        verify(resultStore).delete(job);
        verify(mapper).markExpired(eq("job-expired"), any());
    }

    private ToolboxJob job(String id, String status) {
        ToolboxJob job = new ToolboxJob();
        job.setJobId(id);
        job.setUserId(7L);
        job.setOrganizationId("org-1");
        job.setStatus(status);
        job.setProgress(5);
        job.setToolType("OCR");
        job.setDocumentIdsJson("[\"doc-1\"]");
        job.setDocumentNamesJson("[\"source.txt\"]");
        return job;
    }
}
