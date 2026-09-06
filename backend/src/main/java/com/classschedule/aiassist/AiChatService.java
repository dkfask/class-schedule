package com.classschedule.aiassist;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AiChatService {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public AiChatService(
            @Value("${app.ai.base-url:}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model:}") String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean chatEnabled() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    public String model() {
        return chatEnabled() ? model : null;
    }

    /** 调用 OpenAI 兼容 chat/completions 端点并返回助手回复文本。 */
    @SuppressWarnings("unchecked")
    public String chat(List<Map<String, String>> messages) {
        List<Map<String, String>> payloadMessages = new ArrayList<>();
        payloadMessages.add(
                Map.of(
                        "role",
                        "system",
                        "content",
                        "你是中小学排课系统的助教。用简体中文回答，解释评分、冲突、版本与发布流程，"
                                + "并给出可执行的调整建议；不要编造系统中不存在的功能。"));
        payloadMessages.addAll(messages);
        Map<String, Object> request =
                Map.of("model", model, "messages", payloadMessages, "temperature", 0.3);
        Map<String, Object> response =
                restClient
                        .post()
                        .uri(baseUrl + "/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("AI 服务返回为空");
        }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("AI 服务未返回任何候选回复");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        Object content = message == null ? null : message.get("content");
        if (content == null || content.toString().isBlank()) {
            throw new IllegalStateException("AI 服务返回内容为空");
        }
        return content.toString();
    }
}
