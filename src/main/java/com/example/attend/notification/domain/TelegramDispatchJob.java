package com.example.attend.notification.domain;

import java.time.Instant;

/** worker가 Telegram으로 전달할 claim 가능한 outbox 작업이다. */
public record TelegramDispatchJob(
        long id,
        long accountId,
        long chatId,
        Instant connectionUpdatedAt,
        String messageText,
        int attemptCount,
        long claimVersion) {
}
