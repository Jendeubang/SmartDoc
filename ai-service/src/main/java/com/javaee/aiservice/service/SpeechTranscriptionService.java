package com.javaee.aiservice.service;

import com.javaee.aiservice.agent.ChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** Calls an OpenAI-compatible speech-to-text endpoint, then uses DeepSeek to create meeting notes. */
@Service
public class SpeechTranscriptionService {
    private final ChatService chatService;

    @Value("${ai.asr.url:}")
    private String asrUrl;
    @Value("${ai.asr.api-key:}")
    private String asrApiKey;
    @Value("${ai.asr.model:whisper-1}")
    private String asrModel;

    public SpeechTranscriptionService(ChatService chatService) { this.chatService = chatService; }

    public MeetingMinutes transcribeAndSummarize(MultipartFile audio, String title, String language) {
        if (audio == null || audio.isEmpty()) throw new IllegalArgumentException("请上传录音文件");
        if (asrUrl == null || asrUrl.isBlank() || asrApiKey == null || asrApiKey.isBlank()) {
            throw new IllegalStateException("录音转写尚未配置。请在 .env 中配置 ASR_BASE_URL、ASR_API_KEY 和 ASR_MODEL；DeepSeek Chat 仅用于生成会议纪要，不能直接识别音频。");
        }
        String transcript = transcribe(audio, language);
        if (transcript.isBlank()) throw new IllegalStateException("语音识别服务没有返回可用文字，请检查录音格式或 ASR 模型配置");
        String meetingTitle = title == null || title.isBlank() ? "会议纪要" : title.trim();
        String prompt = "你是专业会议秘书。根据以下会议录音转写生成《" + meetingTitle + "》。"
                + "不得编造转写中没有出现的事实、时间、负责人或结论；不确定的信息请写‘待确认’。"
                + "使用清晰的 Markdown，严格包含：\n# " + meetingTitle
                + "\n## 会议摘要\n## 关键讨论\n## 决策事项\n## 待办事项（用表格：事项｜负责人｜截止时间｜状态）\n## 风险与待确认事项\n\n"
                + "会议转写：\n" + transcript;
        return new MeetingMinutes(transcript, chatService.callChatApiWithModelCode(prompt, "deepseek-chat"), asrModel);
    }

    @SuppressWarnings("unchecked")
    private String transcribe(MultipartFile audio, String language) {
        try {
            ByteArrayResource resource = new ByteArrayResource(audio.getBytes()) {
                @Override public String getFilename() { return audio.getOriginalFilename() == null ? "recording.wav" : audio.getOriginalFilename(); }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource); body.add("model", asrModel); body.add("response_format", "json");
            if (language != null && !language.isBlank() && !"auto".equalsIgnoreCase(language)) body.add("language", language);
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(20)); factory.setReadTimeout(Duration.ofMinutes(10));
            Map<String, Object> response = RestClient.builder().requestFactory(factory).build().post().uri(asrUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + asrApiKey).contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body).retrieve().body(Map.class);
            if (response == null) return "";
            Object text = response.get("text");
            if (text == null && response.get("data") instanceof Map<?, ?> data) text = data.get("text");
            if (text == null) text = response.get("transcript");
            return text == null ? "" : String.valueOf(text).trim();
        } catch (IOException e) { throw new IllegalStateException("无法读取录音文件", e); }
        catch (Exception e) { throw new IllegalStateException("录音转写失败：" + rootMessage(e), e); }
    }
    private String rootMessage(Throwable error) { Throwable current=error; while(current.getCause()!=null) current=current.getCause(); return current.getMessage()==null ? error.getClass().getSimpleName() : current.getMessage(); }
    public record MeetingMinutes(String transcript, String minutes, String asrModel) {}
}