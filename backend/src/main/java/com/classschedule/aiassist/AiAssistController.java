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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-assist")
public class AiAssistController {
    private final AiChatService chat;
    private final AiDiagnosticsService diagnostics;
    private final ScheduleRepository repository;

    public AiAssistController(
            AiChatService chat, AiDiagnosticsService diagnostics, ScheduleRepository repository) {
        this.chat = chat;
        this.diagnostics = diagnostics;
        this.repository = repository;
    }

    public record ChatTurn(@NotBlank @Size(max = 4000) String content) {}

    public record ChatRequest(@NotEmpty @Size(max = 20) List<@Valid ChatTurn> messages) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "diagnosticsEnabled", true,
                "chatEnabled", chat.chatEnabled(),
                "model", chat.model() == null ? "" : chat.model());
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
                            "未配置 AI 服务：请设置 APP_AI_BASE_URL / APP_AI_API_KEY / APP_AI_MODEL"));
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
