package com.example.attend.notification.infrastructure.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Telegram 오류가 사용자 연결 삭제로 과도하게 확대되지 않는지 검증한다. */
class TelegramBotClientTest {

    @Test
    void invalidBotTokenIsRetriedWithoutRevokingAnyAdministratorConnection() {
        TelegramDeliveryFailure failure = TelegramBotClient.fromHttpFailure(
                HttpStatus.UNAUTHORIZED,
                "{\"ok\":false,\"description\":\"Unauthorized\"}",
                null);

        assertThat(failure.permanent()).isFalse();
        assertThat(failure.revokeConnection()).isFalse();
    }

    @Test
    void malformedMessageIsRetriedWithoutRevokingTheConnection() {
        TelegramDeliveryFailure failure = TelegramBotClient.fromHttpFailure(
                HttpStatus.BAD_REQUEST,
                "{\"ok\":false,\"description\":\"Bad Request: can't parse entities\"}",
                null);

        assertThat(failure.permanent()).isFalse();
        assertThat(failure.revokeConnection()).isFalse();
    }

    @Test
    void onlyAnExplicitlyInvalidChatRevokesTheConnection() {
        TelegramDeliveryFailure failure = TelegramBotClient.fromHttpFailure(
                HttpStatus.FORBIDDEN,
                "{\"ok\":false,\"description\":\"Forbidden: bot was blocked by the user\"}",
                null);

        assertThat(failure.permanent()).isTrue();
        assertThat(failure.revokeConnection()).isTrue();
    }

    @Test
    void retryAfterIsPreservedForRateLimiting() {
        TelegramDeliveryFailure failure = TelegramBotClient.fromHttpFailure(
                HttpStatus.TOO_MANY_REQUESTS,
                "{\"ok\":false,\"parameters\":{\"retry_after\":9}}",
                null);

        assertThat(failure.permanent()).isFalse();
        assertThat(failure.retryAfterSeconds()).isEqualTo(9);
    }
}
