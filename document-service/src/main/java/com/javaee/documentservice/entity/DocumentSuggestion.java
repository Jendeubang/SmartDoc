package com.javaee.documentservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

// 文档修订建议实体：记录用户对文档某段文本提出的修改建议及其审阅处理结果。
// 对应简历第 6 条「企业空间与在线协同」中的建议模式。
@Data
@TableName("document_suggestion")
public class DocumentSuggestion {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String documentId;
    private String organizationId;
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
