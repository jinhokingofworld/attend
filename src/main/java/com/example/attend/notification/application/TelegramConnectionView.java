package com.example.attend.notification.application;

import java.time.Instant;

/** 본인 계정 알림 설정 화면에 안전하게 전달할 상태다. */
public record TelegramConnectionView(
        String state,
        Instant linkedAt,
        Instant linkExpiresAt,
        String testRequestState) {
}
