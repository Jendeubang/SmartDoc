package com.javaee.aiservice.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档纠错结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextCorrectVO {
    /** 纠错后的文本 */
    private String correctedText;
    /** 原始文本长度 */
    private Integer originalLength;
    /** 纠错后文本长度 */
    private Integer correctedLength;
    /** 纠错模式：error-correction（纠错） / polish（润色） */
    private String mode;
    /** 具体问题列表（仅纠错模式下有值） */
    private String issues;
}
