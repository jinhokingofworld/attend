package com.example.attend.notification.domain;

import java.time.Instant;

/** 계정의 현재 Telegram 개인 채팅 연결 상태다. */
public record TelegramConnectionRow(long accountId, Instant linkedAt) {
}
