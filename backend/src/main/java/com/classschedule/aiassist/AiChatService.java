package com.classschedule.aiassist;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AiChatService {
    private static final String SYSTEM_PROMPT =
            "你是中小学排课系统的助教。用简体中文回答，解释评分、冲突、版本与发布流程，"
                    + "并给出可执行的调整建议；不要编造系统中不存在的功能。";

    private final AiModelSettingsService settings;
    private final RestClient restClient;

    public AiChatService(AiModelSettingsService settings) {
        this.settings = settings;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean chatEnabled() {
        return settings.current().chatEnabled();
    }

    public String model() {
        AiModelSettingsService.Snapshot current = settings.current();
        return current.chatEnabled() ? current.model() : null;
    }

    /** 调用配置的兼容端点并返回助手回复文本。 */
    @SuppressWarnings("unchecked")
    public String chat(List<Map<String, String>> messages) {
        AiModelSettingsService.Snapshot current = settings.current();
        if (!current.chatEnabled()) throw new IllegalStateException("AI 服务未配置");
        Map<String, Object> request = buildRequest(current, messages);
        Map<String, Object> response =
                restClient
                        .post()
                        .uri(endpoint(current))
                        .headers(
                                headers -> {
                                    if ("ANTHROPIC".equals(current.protocol())) {
                                        headers.set("X-Api-Key", current.apiKey());
                                        headers.set("anthropic-version", "2023-06-01");
                                    } else {
                                        headers.set("Authorization", "Bearer " + current.apiKey());
                                    }
                                })
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("AI 服务返回为空");
        }
        if ("ANTHROPIC".equals(current.protocol())) {
            return anthropicReply(response);
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

    private Map<String, Object> buildRequest(
            AiModelSettingsService.Snapshot current, List<Map<String, String>> messages) {
        if ("ANTHROPIC".equals(current.protocol())) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", current.model());
            request.put("max_tokens", 2048);
            request.put("system", SYSTEM_PROMPT);
            request.put("messages", messages);
            request.put("temperature", 0.3);
            return request;
        }
        List<Map<String, String>> payloadMessages = new ArrayList<>();
        payloadMessages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        payloadMessages.addAll(messages);
        return Map.of("model", current.model(), "messages", payloadMessages, "temperature", 0.3);
    }

    private String endpoint(AiModelSettingsService.Snapshot current) {
        return "ANTHROPIC".equals(current.protocol())
                ? current.baseUrl() + "/v1/messages"
                : current.baseUrl() + "/chat/completions";
    }

    private String anthropicReply(Map<String, Object> response) {
        Object rawContent = response.get("content");
        if (!(rawContent instanceof List<?> contentBlocks)) {
            throw new IllegalStateException("AI 服务未返回任何文本内容");
        }
        StringBuilder reply = new StringBuilder();
        for (Object rawBlock : contentBlocks) {
            if (!(rawBlock instanceof Map<?, ?> block)) continue;
            if ("text".equals(block.get("type")) && block.get("text") != null) {
                reply.append(block.get("text"));
            }
        }
        if (reply.isEmpty()) throw new IllegalStateException("AI 服务未返回任何文本内容");
        return reply.toString();
    }
}
