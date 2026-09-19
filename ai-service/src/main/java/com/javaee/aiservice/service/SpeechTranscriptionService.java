package com.javaee.aiservice.service;

import com.javaee.aiservice.agent.ChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 语音转写服务（对应简历第 5 条「录音 ASR 转会议纪要」）：DashScope Qwen ASR 转写录音 + DeepSeek 生成结构化会议纪要
/** DashScope Qwen ASR transcription, followed by DeepSeek meeting-minute generation. */
@Service
public class SpeechTranscriptionService {
    private final ChatService chatService;

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;
    @Value("${ai.dashscope-asr.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String dashScopeAsrUrl;
    @Value("${ai.dashscope-asr.model:qwen3-asr-flash}")
    private String dashScopeAsrModel;
    @Value("${ai.dashscope-asr.max-inline-bytes:7340032}")
    private long maxInlineBytes;

    public SpeechTranscriptionService(ChatService chatService) { this.chatService = chatService; }

    // 核心入口：校验录音 → ASR 转写 → 构造提示词交由 DeepSeek 生成结构化会议纪要
    public MeetingMinutes transcribeAndSummarize(MultipartFile audio, String title, String language) {
        if (audio == null || audio.isEmpty()) throw new IllegalArgumentException("请上传录音文件");
        if (dashScopeApiKey == null || dashScopeApiKey.isBlank()) {
            throw new IllegalStateException("录音转写尚未配置。请在 .env 中填写 DASHSCOPE_API_KEY，然后重建 ai-service。");
        }
        if (audio.getSize() > maxInlineBytes) {
            throw new IllegalArgumentException("当前 DashScope 直传模式仅支持约 7MB 以内的录音（建议 5 分钟内）。更长录音请使用后续的 OSS 异步转写模式。");
        }
        String transcript = transcribe(audio, language);
        if (transcript.isBlank()) throw new IllegalStateException("DashScope 没有返回可用文字，请检查录音格式或语言设置");
        String meetingTitle = title == null || title.isBlank() ? "会议纪要" : title.trim();
        String prompt = "你是专业会议秘书。根据以下会议录音转写生成《" + meetingTitle + "》。"
                + "不得编造转写中没有出现的事实、时间、负责人或结论；不确定的信息请写‘待确认’。"
                + "使用清晰的 Markdown，严格包含：\n# " + meetingTitle
                + "\n## 会议摘要\n## 关键讨论\n## 决策事项\n## 待办事项（用表格：事项｜负责人｜截止时间｜状态）\n## 风险与待确认事项\n\n会议转写：\n" + transcript;
        return new MeetingMinutes(transcript, chatService.callChatApiWithModelCode(prompt, "deepseek-chat"), dashScopeAsrModel);
    }

    @SuppressWarnings("unchecked")
    // 调用 DashScope 兼容模式接口：录音 Base64 编码为 data URI，以 input_audio 消息发送并解析转写结果
    private String transcribe(MultipartFile audio, String language) {
        try {
            String mime = audio.getContentType() == null || audio.getContentType().isBlank() ? "audio/mpeg" : audio.getContentType();
            String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(audio.getBytes());
            Map<String, Object> inputAudio = Map.of("data", dataUri);
            Map<String, Object> content = Map.of("type", "input_audio", "input_audio", inputAudio);
            Map<String, Object> message = Map.of("role", "user", "content", List.of(content));
            Map<String, Object> options = new HashMap<>();
            options.put("enable_itn", false);
            if (language != null && !language.isBlank() && !"auto".equalsIgnoreCase(language)) options.put("language", language);
            Map<String, Object> body = new HashMap<>();
            body.put("model", dashScopeAsrModel); body.put("messages", List.of(message)); body.put("stream", false); body.put("asr_options", options);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(20)); factory.setReadTimeout(Duration.ofMinutes(3));
            Map<String, Object> response = RestClient.builder().requestFactory(factory).build().post().uri(dashScopeAsrUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + dashScopeApiKey).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(Map.class);
            return extractTranscript(response);
        } catch (IOException e) { throw new IllegalStateException("无法读取录音文件", e); }
        catch (Exception e) { throw new IllegalStateException("DashScope 录音转写失败：" + rootMessage(e), e); }
    }

    @SuppressWarnings("unchecked")
    // 从 DashScope 响应中兼容多种结构提取转写文本（choices/output/text）
    private String extractTranscript(Map<String, Object> response) {
        if (response == null) return "";
        Object choices = response.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> choice) {
            Object message = choice.get("message");
            if (message instanceof Map<?, ?> messageMap && messageMap.get("content") != null) return String.valueOf(messageMap.get("content")).trim();
        }
        Object output = response.get("output");
        if (output instanceof Map<?, ?> outputMap && outputMap.get("text") != null) return String.valueOf(outputMap.get("text")).trim();
        if (response.get("text") != null) return String.valueOf(response.get("text")).trim();
        throw new IllegalStateException("DashScope 响应中未找到转写文本");
    }

    // 递归取出异常链最底层的错误信息，便于定位真实失败原因
    private String rootMessage(Throwable error) { Throwable current=error; while(current.getCause()!=null) current=current.getCause(); return current.getMessage()==null ? error.getClass().getSimpleName() : current.getMessage(); }
    public record MeetingMinutes(String transcript, String minutes, String asrModel) {}
}