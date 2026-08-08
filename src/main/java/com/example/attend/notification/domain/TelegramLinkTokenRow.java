package com.example.attend.notification.domain;

import java.time.Instant;

/** webhook에서 잠그고 소비할 Telegram 연결 token이다. */
public record TelegramLinkTokenRow(long id, long accountId, Instant expiresAt) {
}
