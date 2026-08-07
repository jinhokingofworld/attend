package com.example.attend.notification.web;

import com.example.attend.config.TelegramProperties;
import com.example.attend.notification.application.TelegramConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Telegram이 전달하는 private-chat /start update만 연결 용도로 수신한다. */
@RestController
public final class TelegramWebhookController {
    private final TelegramProperties properties;
    private final TelegramConnectionService connectionService;

    public TelegramWebhookController(
            TelegramProperties properties,
            TelegramConnectionService connectionService) {
        this.properties = properties;
        this.connectionService = connectionService;
    }

    @PostMapping("/api/v1/telegram/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
            @RequestBody JsonNode update) {
        if (!properties.enabled() || !matchesSecret(secret)) {
            return ResponseEntity.status(403).build();
        }
        JsonNode message = update.path("message");
        String text = message.path("text").asText("");
        if (!"private".equals(message.path("chat").path("type").asText())
                || !text.startsWith("/start ")) {
            return ResponseEntity.ok().build();
        }
        long updateId = update.path("update_id").asLong(0);
        long chatId = message.path("chat").path("id").asLong(0);
        long userId = message.path("from").path("id").asLong(0);
        String token = text.substring("/start ".length()).trim();
        if (updateId > 0 && chatId != 0 && userId != 0 && !token.isEmpty()) {
            connectionService.consumeStart(updateId, token, chatId, userId);
        }
        return ResponseEntity.ok().build();
    }

    private boolean matchesSecret(String actual) {
        if (actual == null || properties.webhookSecret() == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                properties.webhookSecret().getBytes(StandardCharsets.UTF_8));
    }
}
