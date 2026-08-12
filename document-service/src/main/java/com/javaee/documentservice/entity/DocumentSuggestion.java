package com.javaee.documentservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_suggestion")
public class DocumentSuggestion {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String documentId;
    private Long userId;
    private String originalText;
    private String suggestedText;
    private Integer startOffset;
    private Integer endOffset;
    private String reason;
    private String status;
    private Long reviewedBy;
    private String reviewComment;
    private LocalDateTime createTime;
    private LocalDateTime reviewTime;
}
