package com.javaee.aiservice.dto;

/**
 * 【简历：文档摘要请求 DTO】
 * AI Skills 文档摘要能力的请求参数。
 */

import lombok.Data;

@Data
public class TextSummarizeDTO {
    private String content;
    private Integer maxLength;
}
