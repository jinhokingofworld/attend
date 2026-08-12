package com.example.attend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 출석 관리자 Bot과 분리된 개발자 운영 알림 Bot 설정이다. */
@ConfigurationProperties(prefix = "attendance.operations.telegram")
public record OperationalTelegramProperties(
        boolean enabled,
        String botToken,
        long chatId) {

    public OperationalTelegramProperties {
        botToken = normalize(botToken);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
