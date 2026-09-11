package com.classschedule.aiassist;

import com.classschedule.schedule.ScheduleRepository;
import com.classschedule.schedule.ScheduleVersionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-assist")
public class AiAssistController {
    private final AiChatService chat;
    private final AiDiagnosticsService diagnostics;
    private final AiModelSettingsService modelSettings;
    private final ScheduleRepository repository;

    public AiAssistController(
            AiChatService chat,
            AiDiagnosticsService diagnostics,
            AiModelSettingsService modelSettings,
            ScheduleRepository repository) {
        this.chat = chat;
        this.diagnostics = diagnostics;
        this.modelSettings = modelSettings;
        this.repository = repository;
    }

    public record ChatTurn(@NotBlank @Size(max = 4000) String content) {}

    public record ChatRequest(@NotEmpty @Size(max = 20) List<@Valid ChatTurn> messages) {}

    public record SettingsRequest(
            @Size(max = 512) String baseUrl,
            @Size(max = 16) String protocol,
            @Size(max = 128) String model,
            @Size(max = 4096) String apiKey,
            boolean clearApiKey) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "diagnosticsEnabled", true,
                "chatEnabled", chat.chatEnabled(),
                "model", chat.model() == null ? "" : chat.model());
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('AI_CONFIG_MANAGE')")
    public AiModelSettingsService.PublicSettings settings() {
        return modelSettings.publicSettings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('AI_CONFIG_MANAGE')")
    public ResponseEntity<?> updateSettings(
            @Valid @RequestBody SettingsRequest request, Authentication authentication) {
        try {
            modelSettings.update(
                    request.baseUrl(),
                    request.protocol(),
                    request.model(),
                    request.apiKey(),
                    request.clearApiKey(),
                    authentication.getName());
            return ResponseEntity.ok(modelSettings.publicSettings());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "AI_CONFIG_INVALID", "message", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "AI_CONFIG_UNAVAILABLE", "message", exception.getMessage()));
        }
    }

    @GetMapping("/diagnostics/{versionId}")
    public ResponseEntity<?> diagnostics(@PathVariable long versionId, Authentication authentication) {
        try {
            if (!repository.canAccessVersion(versionId, authentication.getName())) {
                return ResponseEntity.notFound().build();
            }
            ScheduleVersionView version = repository.findVersion(versionId);
            return ResponseEntity.ok(diagnostics.diagnose(version));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest request) {
        if (!chat.chatEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "code",
                            "AI_NOT_CONFIGURED",
                            "message",
                            "未配置 AI 服务：请在系统设置中配置接口地址、协议、API Key 和模型"));
        }
        try {
            List<Map<String, String>> messages =
                    request.messages().stream()
                            .map(turn -> Map.of("role", "user", "content", turn.content()))
                            .toList();
            return ResponseEntity.ok(Map.of("reply", chat.chat(messages)));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", "AI 服务调用失败: " + exception.getMessage()));
        }
    }
}
