package com.javaee.aiservice.service;

import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.agent.PromptEngineeringService;
import com.javaee.aiservice.dto.KeywordExtractDTO;
import com.javaee.aiservice.dto.TextAnalyzeDTO;
import com.javaee.aiservice.dto.TextSummarizeDTO;
import com.javaee.aiservice.vo.KeywordExtractVO;
import com.javaee.aiservice.vo.KeywordVO;
import com.javaee.aiservice.vo.TextAnalyzeVO;
import com.javaee.aiservice.vo.TextCorrectVO;
import com.javaee.aiservice.vo.TextSummarizeVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI服务
 * 提供文档摘要、纠错、关键词提取等功能，支持多模型选择
 */
@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private PromptEngineeringService promptEngineeringService;

    /**
     * 文档摘要（使用默认模型）
     * @param dto 请求参数
     * @return 摘要结果
     */
    public TextSummarizeVO summarize(TextSummarizeDTO dto) {
        return summarize(dto, null);
    }

    /**
     * 文档摘要（使用指定模型）
     * @param dto 请求参数
     * @param model 模型代码
     * @return 摘要结果
     */
    public TextSummarizeVO summarize(TextSummarizeDTO dto, String model) {
        log.info("开始文档摘要，文本长度={}, 模型={}", dto.getContent().length(), model);

        String summary = chatService.callChatApiWithModelCode(
            promptEngineeringService.createSummarizePrompt(dto.getContent(), dto.getMaxLength()),
            model
        );

        TextSummarizeVO vo = new TextSummarizeVO();
        vo.setSummary(summary);
        vo.setOriginalLength(dto.getContent().length());
        vo.setSummaryLength(summary.length());
        vo.setCompressionRatio((double) summary.length() / dto.getContent().length());

        log.info("文档摘要完成");
        return vo;
    }

    /**
     * 文档分析
     * @param dto 请求参数
     * @return 分析结果
     */
    public TextAnalyzeVO analyze(TextAnalyzeDTO dto) {
        log.info("开始文档分析");

        String content = dto.getContent();
        
        TextAnalyzeVO vo = new TextAnalyzeVO();
        vo.setTotalCharacters(content.length());
        
        int chineseCount = 0, englishCount = 0, digitCount = 0, spaceCount = 0, punctuationCount = 0;
        
        for (char c : content.toCharArray()) {
            if (Character.isIdeographic(c)) {
                chineseCount++;
            } else if (Character.isLetter(c)) {
                englishCount++;
            } else if (Character.isDigit(c)) {
                digitCount++;
            } else if (Character.isWhitespace(c)) {
                spaceCount++;
            } else if (isPunctuation(c)) {
                punctuationCount++;
            }
        }
        
        vo.setChineseCharacters(chineseCount);
        vo.setEnglishCharacters(englishCount);
        vo.setDigits(digitCount);
        vo.setSpaces(spaceCount);
        vo.setPunctuations(punctuationCount);
        vo.setLines(content.split("\n").length);
        
        log.info("文档分析完成");
        return vo;
    }

    /**
     * 判断是否为标点符号
     */
    private boolean isPunctuation(char c) {
        return "，。！？；：、\"\"''（）{}[]<>《》·".indexOf(c) >= 0 ||
                ",.!?;:\"'(){}[]<>".indexOf(c) >= 0;
    }

    /**
     * 关键词提取（使用默认模型）
     * @param dto 请求参数
     * @return 关键词结果
     */
    public KeywordExtractVO extractKeywords(KeywordExtractDTO dto) {
        return extractKeywords(dto, null);
    }

    /**
     * 关键词提取（使用指定模型）
     * @param dto 请求参数
     * @param model 模型代码
     * @return 关键词结果
     */
    public KeywordExtractVO extractKeywords(KeywordExtractDTO dto, String model) {
        log.info("开始关键词提取，模型={}", model);

        String keywordsStr = chatService.callChatApiWithModelCode(
            promptEngineeringService.createKeywordExtractPrompt(dto.getContent(), dto.getCount()),
            model
        );

        List<KeywordVO> keywords = Arrays.stream(keywordsStr.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(word -> new KeywordVO(word, 1.0, "keyword"))
            .collect(Collectors.toList());

        KeywordExtractVO vo = new KeywordExtractVO();
        vo.setKeywords(keywords);
        vo.setTotalCount(keywords.size());

        log.info("关键词提取完成，共{}个关键词", keywords.size());
        return vo;
    }

    /**
     * 文档纠错（使用默认模型）
     * @param dto 请求参数
     * @return 纠错结果
     */
    public TextCorrectVO correct(TextAnalyzeDTO dto) {
        return correct(dto, null);
    }

    /**
     * 文档纠错（使用指定模型）
     * @param dto 请求参数
     * @param model 模型代码
     * @return 纠错结果
     */
    public TextCorrectVO correct(TextAnalyzeDTO dto, String model) {
        log.info("开始文档纠错，文本长度={}, 模型={}", dto.getContent().length(), model);

        String correctedResult = chatService.callChatApiWithModelCode(
            promptEngineeringService.createCorrectPrompt(dto.getContent()),
            model
        );

        TextCorrectVO vo = new TextCorrectVO();
        vo.setOriginalLength(dto.getContent().length());
        vo.setMode(correctedResult.trim().contains("未发现明显问题") ? "clean" : "error-correction");

        if ("clean".equals(vo.getMode())) {
            vo.setCorrectedText(dto.getContent());
            vo.setCorrectedLength(dto.getContent().length());
            vo.setIssues(null);
        } else {
            vo.setIssues(correctedResult);
            vo.setCorrectedText(null);
            vo.setCorrectedLength(null);
        }

        log.info("文档纠错完成, 模式={}", vo.getMode());
        return vo;
    }
}