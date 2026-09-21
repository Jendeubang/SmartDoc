package com.javaee.aiservice.rag;

/**
 * 【简历：语义分段策略】
 * 基于段落间语义相似度进行合并/分割，将语义连贯的文本聚为一个分段。
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SemanticSegmentStrategy implements SegmentStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticSegmentStrategy.class);

    private static final int DEFAULT_TARGET_CHUNK_SIZE = 300;
    private static final int DEFAULT_MIN_CHUNK_SIZE = 100;
    private static final int DEFAULT_SIMILARITY_THRESHOLD = 75;

    @Autowired
    private DocumentVectorizer vectorizer;

    private final int targetChunkSize;
    private final int minChunkSize;
    private final int similarityThreshold;

    public SemanticSegmentStrategy() {
        this(DEFAULT_TARGET_CHUNK_SIZE, DEFAULT_MIN_CHUNK_SIZE, DEFAULT_SIMILARITY_THRESHOLD);
    }

    public SemanticSegmentStrategy(int targetChunkSize, int minChunkSize, int similarityThreshold) {
        this.targetChunkSize = targetChunkSize;
        this.minChunkSize = minChunkSize;
        this.similarityThreshold = similarityThreshold;
    }

    // 语义分段主流程：拆句 -> 按相似度聚类成组 -> 生成分段并串联相邻分块链接
    @Override
    public List<Segment> segment(String documentId, String content) {
        log.info("执行语义分段: documentId={}, targetChunkSize={}, threshold={}",
                documentId, targetChunkSize, similarityThreshold);

        List<Segment> segments = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            log.warn("文档内容为空，跳过分段");
            return segments;
        }

        List<Sentence> sentences = splitIntoSentences(content);
        log.debug("句子拆分完成: {} 个句子", sentences.size());

        if (sentences.isEmpty()) {
            return segments;
        }

        List<SentenceGroup> groups = groupSentencesBySemantic(sentences);
        log.debug("语义分组完成: {} 个分组", groups.size());

        int segmentIndex = 0;
        for (SentenceGroup group : groups) {
            String segmentContent = buildSegmentContent(group);

            if (segmentContent.length() < minChunkSize && segmentIndex > 0) {
                if (!segments.isEmpty()) {
                    Segment lastSegment = segments.get(segments.size() - 1);
                    String mergedContent = lastSegment.getContent() + "\n" + segmentContent;
                    lastSegment.setContent(mergedContent);
                    lastSegment.setCharCount(mergedContent.length());
                    log.debug("合并到上一个分段: segmentId={}, newCharCount={}",
                            lastSegment.getSegmentId(), mergedContent.length());
                }
            } else {
                Segment segment = new Segment(
                        generateSegmentId(documentId, segmentIndex),
                        documentId,
                        segmentContent,
                        segmentIndex,
                        group.getTheme()
                );
                segments.add(segment);
                log.debug("生成分段: segmentId={}, theme={}, charCount={}",
                        segment.getSegmentId(), group.getTheme(), segmentContent.length());
                segmentIndex++;
            }
        }

        // [NEW] 设置分块间的前后链接，用于检索时的上下文贯通
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                segments.get(i).setPrevChunkId(segments.get(i - 1).getSegmentId());
            }
            if (i < segments.size() - 1) {
                segments.get(i).setNextChunkId(segments.get(i + 1).getSegmentId());
            }
        }

        log.info("语义分段完成: documentId={}, 生成{}个分段", documentId, segments.size());
        return segments;
    }

    // 将文档按段落与句末标点拆分成句子，并记录每个句子的字符偏移
    private List<Sentence> splitIntoSentences(String content) {
        List<Sentence> sentences = new ArrayList<>();

        String[] lines = content.split("\n");
        StringBuilder currentParagraph = new StringBuilder();
        int charPosition = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                if (currentParagraph.length() > 0) {
                    charPosition += currentParagraph.length() + 1;
                    currentParagraph.setLength(0);
                }
                continue;
            }

            if (currentParagraph.length() > 0) {
                currentParagraph.append(" ");
            }
            currentParagraph.append(line);

            if (currentParagraph.length() >= targetChunkSize / 2 ||
                    line.endsWith("。") || line.endsWith("！") || line.endsWith("？") ||
                    line.endsWith(".") || line.endsWith("!") || line.endsWith("?")) {

                sentences.add(new Sentence(currentParagraph.toString(), charPosition));
                charPosition += currentParagraph.length();
                currentParagraph.setLength(0);
            }
        }

        if (currentParagraph.length() > 0) {
            sentences.add(new Sentence(currentParagraph.toString(), charPosition));
        }

        return sentences;
    }

    // 用 Embedding 余弦相似度把语义相近的句子聚到同一分组
    private List<SentenceGroup> groupSentencesBySemantic(List<Sentence> sentences) {
        List<SentenceGroup> groups = new ArrayList<>();

        if (sentences.isEmpty()) {
            return groups;
        }

        SentenceGroup currentGroup = new SentenceGroup(sentences.get(0));
        groups.add(currentGroup);

        for (int i = 1; i < sentences.size(); i++) {
            Sentence sentence = sentences.get(i);

            float[] currentVector = vectorizer.vectorize(currentGroup.getTheme());
            float[] sentenceVector = vectorizer.vectorize(sentence.getText());

            float similarity = cosineSimilarity(currentVector, sentenceVector);
            int similarityPercent = (int)(similarity * 100);

            if (similarityPercent >= similarityThreshold) {
                currentGroup.addSentence(sentence);
                log.debug("句子加入当前分组: similarity={}%, groupSize={}",
                        similarityPercent, currentGroup.getSentenceCount());
            } else {
                currentGroup = new SentenceGroup(sentence);
                groups.add(currentGroup);
                log.debug("创建新分组: theme={}", currentGroup.getTheme());
            }
        }

        return groups;
    }

    // 计算两个向量的余弦相似度，用于判断句子间语义相近程度
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0f;
        }

        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0.0f;
        }

        return dotProduct / (float)(Math.sqrt(normA) * Math.sqrt(normB));
    }

    // 将分组内的句子拼接为一个分段正文
    private String buildSegmentContent(SentenceGroup group) {
        StringBuilder sb = new StringBuilder();
        for (Sentence sentence : group.getSentences()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(sentence.getText());
        }
        return sb.toString();
    }

    private String generateSegmentId(String documentId, int index) {
        return documentId + "_semantic_" + index;
    }

    @Override
    public String getStrategyName() {
        return "SEMANTIC";
    }

    public int getTargetChunkSize() {
        return targetChunkSize;
    }

    public int getSimilarityThreshold() {
        return similarityThreshold;
    }

    private static class Sentence {
        private final String text;
        private final int charPosition;

        Sentence(String text, int charPosition) {
            this.text = text;
            this.charPosition = charPosition;
        }

        String getText() { return text; }
        int getCharPosition() { return charPosition; }
    }

    private static class SentenceGroup {
        private final List<Sentence> sentences = new ArrayList<>();
        private String theme;
        private final String initialText;

        SentenceGroup(Sentence firstSentence) {
            this.initialText = firstSentence.getText();
            this.theme = extractTheme(firstSentence.getText());
            this.sentences.add(firstSentence);
        }

        void addSentence(Sentence sentence) {
            this.sentences.add(sentence);
        }

        private String extractTheme(String text) {
            int length = Math.min(50, text.length());
            return text.substring(0, length);
        }

        List<Sentence> getSentences() { return sentences; }
        String getTheme() { return theme; }
        int getSentenceCount() { return sentences.size(); }
    }
}