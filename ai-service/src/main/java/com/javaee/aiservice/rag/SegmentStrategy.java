package com.javaee.aiservice.rag;

/**
 * 【简历：分段策略接口】
 * RAG 分段策略的统一接口，定义 Segment 内部类表示单个分段。
 */

import java.util.List;

public interface SegmentStrategy {

    // 执行分段：将文档切分为多个分段
    List<Segment> segment(String documentId, String content);

    // 返回策略名称标识
    String getStrategyName();

    // 分段模型：封装单个分段的 ID、正文、标题及相邻分块链接（用于检索时上下文贯通）
    static class Segment {
        private String segmentId;
        private String documentId;
        private String content;
        private int index;
        private String title;
        private int charCount;
        // [NEW] 前后相邻分块链接，用于检索时的上下文贯通
        private String prevChunkId;
        private String nextChunkId;

        public Segment(String documentId, String content, int index) {
            this.segmentId = generateSegmentId(documentId, index);
            this.documentId = documentId;
            this.content = content;
            this.index = index;
            this.charCount = content != null ? content.length() : 0;
        }

        public Segment(String segmentId, String documentId, String content, int index, String title) {
            this.segmentId = segmentId;
            this.documentId = documentId;
            this.content = content;
            this.index = index;
            this.title = title;
            this.charCount = content != null ? content.length() : 0;
        }

        private String generateSegmentId(String documentId, int index) {
            return documentId + "_seg_" + index;
        }

        public String getSegmentId() { return segmentId; }
        public void setSegmentId(String segmentId) { this.segmentId = segmentId; }
        public String getDocumentId() { return documentId; }
        public void setDocumentId(String documentId) { this.documentId = documentId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public int getCharCount() { return charCount; }
        public void setCharCount(int charCount) { this.charCount = charCount; }
        // [NEW]
        public String getPrevChunkId() { return prevChunkId; }
        public void setPrevChunkId(String prevChunkId) { this.prevChunkId = prevChunkId; }
        public String getNextChunkId() { return nextChunkId; }
        public void setNextChunkId(String nextChunkId) { this.nextChunkId = nextChunkId; }
    }
}
