package com.classschedule.aiassist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiChatServiceTest {
    @Test
    void sendsAnthropicCompatibleRequestAndReadsTextBlocks() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/anthropic/v1/messages",
                exchange -> {
                    requestBody.set(
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
                    byte[] response =
                            "{\"content\":[{\"type\":\"thinking\",\"text\":\"internal\"},{\"type\":\"text\",\"text\":\"配置已生效\"}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try {
            AiModelSettingsService settings = mock(AiModelSettingsService.class);
            when(settings.current())
                    .thenReturn(
                            new AiModelSettingsService.Snapshot(
                                    "http://localhost:"
                                            + server.getAddress().getPort()
                                            + "/anthropic",
                                    "ANTHROPIC",
                                    "MiniMax-M2.7",
                                    "secret-value",
                                    true,
                                    "ciphertext",
                                    true,
                                    OffsetDateTime.now()));

            String reply =
                    new AiChatService(settings)
                            .chat(List.of(Map.of("role", "user", "content", "请检查配置")));

            assertEquals("配置已生效", reply);
            assertEquals("secret-value", apiKey.get());
            assertTrue(requestBody.get().contains("\"model\":\"MiniMax-M2.7\""));
            assertTrue(requestBody.get().contains("\"max_tokens\":2048"));
            assertTrue(requestBody.get().contains("\"system\":"));
            assertFalse(requestBody.get().contains("\"role\":\"system\""));
        } finally {
            server.stop(0);
        }
    }
}
