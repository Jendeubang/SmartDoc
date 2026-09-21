package com.javaee.documentservice.dto;

import lombok.Data;

import java.util.List;

/**
 * 文档更新DTO
 */
@Data
public class DocumentUpdateDTO {

    private String id;

    private String title;

    private String content;

    private String category;

    private String organizationId;

    private String departmentId;

    private String folderId;

    private List<String> tags;

    private String changeLog;
}
