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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI服务
 * 提供文档摘要、纠错、关键词提取等功能，支持多模型选择
 */
// 类职责：AI 能力服务，对应简历第4条「AI 智能对话及文档分析」——
// 提供文档摘要 summarize、纠错 correct、关键词提取 extractKeywords 等能力，支持多模型选择。
@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);
    private static final int DEFAULT_KEYWORD_COUNT = 8;
    private static final int MAX_KEYWORD_COUNT = 50;
    private static final Pattern JSON_KEYWORD_PATTERN = Pattern.compile("\\\"(?:word|keyword)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"", Pattern.CASE_INSENSITIVE);

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
    // 文档摘要：调用模型生成摘要，并统计摘要长度与压缩比。
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

        // count 是可选参数。未传时使用默认值，避免 Integer 自动拆箱触发 NPE。
        int requestedCount = dto.getCount() == null
                ? DEFAULT_KEYWORD_COUNT
                : Math.max(1, Math.min(dto.getCount(), MAX_KEYWORD_COUNT));

        String keywordsStr = chatService.callChatApiWithModelCode(
            promptEngineeringService.createKeywordExtractPrompt(dto.getContent(), requestedCount),
            model
        );

        List<KeywordVO> keywords = parseKeywordResponse(keywordsStr, requestedCount);

        KeywordExtractVO vo = new KeywordExtractVO();
        vo.setKeywords(keywords);
        vo.setTotalCount(keywords.size());

        log.info("关键词提取完成，共{}个关键词", keywords.size());
        return vo;
    }

    // 解析模型返回的关键词：优先匹配 JSON 的 word/keyword 字段，失败则按逗号/换行等分隔符拆解纯文本。
    private List<KeywordVO> parseKeywordResponse(String rawResponse, int requestedCount) {
        Set<String> words = new LinkedHashSet<>();
        String raw = rawResponse == null ? "" : rawResponse.trim();

        Matcher jsonMatcher = JSON_KEYWORD_PATTERN.matcher(raw);
        while (jsonMatcher.find()) {
            String word = jsonMatcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim();
            if (!word.isEmpty()) {
                words.add(word);
            }
        }

        if (words.isEmpty()) {
            String plainText = raw.replaceAll("(?s)```(?:json)?", "");
            Arrays.stream(plainText.split("[,，、;；\\r\\n]+"))
                    .map(token -> token.replaceFirst("^\\s*(?:[-*•]|\\d+[.、)、)])\\s*", ""))
                    .map(token -> token.replaceAll("^[\\[\\]{}`'\\\"]+|[\\[\\]{}`'\\\"]+$", ""))
                    .map(String::trim)
                    .filter(token -> !token.isEmpty())
                    .filter(token -> !token.contains("\"word\"") && !token.contains("\"keyword\""))
                    .forEach(words::add);
        }

        int limit = Math.max(1, requestedCount);
        return words.stream()
                .limit(limit)
                .map(word -> new KeywordVO(word, 1.0, "keyword"))
                .collect(Collectors.toList());
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
    // 文档纠错：调用模型返回纠错结果，并按是否"未发现明显问题"区分 clean / error-correction 模式。
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
