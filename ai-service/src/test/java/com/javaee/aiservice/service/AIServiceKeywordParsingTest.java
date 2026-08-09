package com.javaee.aiservice.service;

import com.javaee.aiservice.vo.KeywordVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AIServiceKeywordParsingTest {

    @Test
    void extractsPlainWordsFromJsonKeywordResponse() {
        AIService service = new AIService();
        String response = """
                [
                  {"word":"数字基础设施","score":1,"type":"keyword"},
                  {"word":"智慧城市技术","score":1,"type":"keyword"}
                ]
                """;

        @SuppressWarnings("unchecked")
        List<KeywordVO> keywords = ReflectionTestUtils.invokeMethod(
                service, "parseKeywordResponse", response, 8);

        assertThat(keywords)
                .extracting(KeywordVO::getWord)
                .containsExactly("数字基础设施", "智慧城市技术");
    }

    @Test
    void splitsCommaSeparatedKeywordResponse() {
        AIService service = new AIService();

        @SuppressWarnings("unchecked")
        List<KeywordVO> keywords = ReflectionTestUtils.invokeMethod(
                service, "parseKeywordResponse", "数字基础设施，智慧城市，物联网", 2);

        assertThat(keywords)
                .extracting(KeywordVO::getWord)
                .containsExactly("数字基础设施", "智慧城市");
    }
}