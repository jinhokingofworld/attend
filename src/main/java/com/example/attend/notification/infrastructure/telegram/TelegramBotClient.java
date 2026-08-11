package com.example.attend.notification.infrastructure.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Telegram Bot API sendMessage 호출을 bot token 비노출 경계 안에 둔다. */
@Component
public final class TelegramBotClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final RestClient restClient;

    public TelegramBotClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // This is intentionally far shorter than the two-minute outbox lease.
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 성공한 Telegram message ID를 반환한다. */
    public long sendMessage(String botToken, long chatId, String messageText) {
        try {
            JsonNode response = restClient.post()
                    .uri("https://api.telegram.org/bot{token}/sendMessage", botToken)
                    .body(Map.of("chat_id", chatId, "text", messageText))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean(false)) {
                throw new TelegramDeliveryFailure(false, false, null,
                        "TELEGRAM_INVALID_RESPONSE", null);
            }
            long messageId = response.path("result").path("message_id").asLong(0);
            if (messageId <= 0) {
                throw new TelegramDeliveryFailure(false, false, null,
                        "TELEGRAM_INVALID_RESPONSE", null);
            }
            return messageId;
        } catch (TelegramDeliveryFailure exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw fromHttpFailure(
                    exception.getStatusCode(), exception.getResponseBodyAsString(), exception);
        } catch (RuntimeException exception) {
            throw new TelegramDeliveryFailure(false, false, null,
                    "TELEGRAM_NETWORK_ERROR", exception);
        }
    }

    static TelegramDeliveryFailure fromHttpFailure(
            HttpStatusCode status, String responseBody, Throwable cause) {
        Integer retryAfter = null;
        String description = "";
        try {
            JsonNode body = OBJECT_MAPPER.readTree(responseBody);
            if (body != null && body.has("parameters")) {
                int value = body.path("parameters").path("retry_after").asInt(0);
                retryAfter = value > 0 ? value : null;
            }
            description = body == null ? "" : body.path("description").asText("");
        } catch (Exception ignored) {
            // Telegram body is untrusted; status alone is enough for a safe retry decision.
        }
        FailureDisposition disposition = classify(status, description);
        return new TelegramDeliveryFailure(
                disposition.permanent(), disposition.revokeConnection(), retryAfter,
                "TELEGRAM_HTTP_" + status.value(), cause);
    }

    private static FailureDisposition classify(HttpStatusCode status, String description) {
        String normalized = description.toLowerCase(java.util.Locale.ROOT);
        boolean invalidChat = (status.value() == 400 && normalized.contains("chat not found"))
                || (status.value() == 403 && (normalized.contains("blocked by the user")
                || normalized.contains("user is deactivated")));
        if (invalidChat) {
            return FailureDisposition.DEAD_AND_REVOKE_CONNECTION;
        }
        // A malformed request, expired/rotated bot token (401), and every other
        // client error are not evidence that this administrator's link is invalid.
        // They remain retryable so a corrected global bot configuration can drain
        // the outbox without requiring every administrator to link again.
        return FailureDisposition.RETRY;
    }

    private enum FailureDisposition {
        RETRY(false, false),
        DEAD_AND_REVOKE_CONNECTION(true, true);

        private final boolean permanent;
        private final boolean revokeConnection;

        FailureDisposition(boolean permanent, boolean revokeConnection) {
            this.permanent = permanent;
            this.revokeConnection = revokeConnection;
        }

        boolean permanent() { return permanent; }
        boolean revokeConnection() { return revokeConnection; }
    }
}
