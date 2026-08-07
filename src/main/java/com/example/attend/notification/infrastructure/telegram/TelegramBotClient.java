package com.example.attend.notification.infrastructure.telegram;

import com.example.attend.config.TelegramProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Telegram Bot API sendMessage 호출을 bot token 비노출 경계 안에 둔다. */
@Component
public final class TelegramBotClient {
    private final TelegramProperties properties;
    private final RestClient restClient;

    public TelegramBotClient(TelegramProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    /** 성공한 Telegram message ID를 반환한다. */
    public long sendMessage(long chatId, String messageText) {
        try {
            JsonNode response = restClient.post()
                    .uri("https://api.telegram.org/bot{token}/sendMessage", properties.botToken())
                    .body(Map.of("chat_id", chatId, "text", messageText))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean(false)) {
                throw new TelegramDeliveryFailure(false, null, "TELEGRAM_INVALID_RESPONSE", null);
            }
            long messageId = response.path("result").path("message_id").asLong(0);
            if (messageId <= 0) {
                throw new TelegramDeliveryFailure(false, null, "TELEGRAM_INVALID_RESPONSE", null);
            }
            return messageId;
        } catch (TelegramDeliveryFailure exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            Integer retryAfter = null;
            try {
                JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(exception.getResponseBodyAsString());
                if (body != null && body.has("parameters")) {
                    int value = body.path("parameters").path("retry_after").asInt(0);
                    retryAfter = value > 0 ? value : null;
                }
            } catch (Exception ignored) {
                // Telegram body is untrusted; status alone is enough for a safe retry decision.
            }
            boolean permanent = status.is4xxClientError() && status.value() != 429;
            throw new TelegramDeliveryFailure(
                    permanent, retryAfter, "TELEGRAM_HTTP_" + status.value(), exception);
        } catch (RuntimeException exception) {
            throw new TelegramDeliveryFailure(false, null, "TELEGRAM_NETWORK_ERROR", exception);
        }
    }
}
