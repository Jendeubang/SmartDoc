package com.javaee.fileservice.util;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 文档文本提取工具
 * 使用 Apache POI 从 .docx 文件中提取纯文本内容
 */
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    /**
     * 从 .docx 字节数组中提取纯文本
     * @param fileBytes 文件字节数组
     * @param fileName 文件名（用于判断文件类型）
     * @return 提取的纯文本，解析失败返回空字符串
     */
    public static String extractText(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件内容为空，跳过文本提取");
            return "";
        }
        if (fileName == null) {
            fileName = "";
        }
        String lower = fileName.toLowerCase();

        try {
            if (lower.endsWith(".docx")) {
                return extractDocxText(fileBytes);
            } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                return new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                log.info("不支持的文件类型，跳过文本提取: {}", fileName);
                return "";
            }
        } catch (Exception e) {
            log.warn("文本提取失败: fileName={}, error={}", fileName, e.getMessage());
            return "";
        }
    }

    /** Extracts text from a stream so upload callers do not need to retain the source bytes. */
    public static String extractText(InputStream input, String fileName) {
        if (input == null) {
            log.warn("文件输入流为空，跳过文本提取");
            return "";
        }
        String safeName = fileName == null ? "" : fileName;
        String lower = safeName.toLowerCase();
        try {
            if (lower.endsWith(".docx")) {
                return extractDocxText(input);
            }
            if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            log.info("不支持的文件类型，跳过文本提取: {}", safeName);
            return "";
        } catch (Exception e) {
            log.warn("文本提取失败: fileName={}, error={}", safeName, e.getMessage());
            return "";
        }
    }

    private static String extractDocxText(byte[] fileBytes) throws Exception {
        try (InputStream is = new ByteArrayInputStream(fileBytes);
             XWPFDocument doc = new XWPFDocument(is)) {

            return extractDocxText(doc);
        }
    }

    private static String extractDocxText(InputStream input) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(input)) {
            return extractDocxText(doc);
        }
    }

    private static String extractDocxText(XWPFDocument doc) {

            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(text).append("\n");
                }
            }
            String result = sb.toString().trim();
            log.info("DOCX 解析成功: 提取 {} 字符", result.length());
            return result;
    }
}
