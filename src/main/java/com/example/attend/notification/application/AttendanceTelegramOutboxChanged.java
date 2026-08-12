package com.example.attend.notification.application;

/** 현재 transaction에서 일반 출석 Telegram outbox가 변경됐음을 알린다. */
public record AttendanceTelegramOutboxChanged(int affectedCount) {

    public AttendanceTelegramOutboxChanged {
        if (affectedCount <= 0) {
            throw new IllegalArgumentException("affectedCount must be positive");
        }
    }
}
