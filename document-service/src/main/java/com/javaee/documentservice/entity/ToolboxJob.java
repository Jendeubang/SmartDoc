package com.javaee.documentservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具箱任务持久化实体。任务状态保存在 MySQL，结果正文不进入此表。
 */
@Data
@TableName("toolbox_job")
public class ToolboxJob {

    @TableId(value = "job_id", type = IdType.INPUT)
    private String jobId;

    private Long userId;
    private String organizationId;
    private String toolType;
    private String documentIdsJson;
    private String documentNamesJson;
    private String pages;
    private String status;
    private Integer progress;
    private String message;
    private Integer retryCount;
    private String resultObjectKey;
    private String fileName;
    private String contentType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime expiresAt;
}
