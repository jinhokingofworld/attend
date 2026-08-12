package com.example.attend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Telegram 알림의 외부 비밀값과 worker 동작 설정이다. */
@ConfigurationProperties(prefix = "attendance.telegram")
public record TelegramProperties(
        boolean enabled,
        String botToken,
        String botUsername,
        String webhookSecret,
        String linkTokenPepper,
        int maxAttempts,
        int maxListedMembers) {

    public TelegramProperties {
        botToken = normalize(botToken);
        botUsername = normalize(botUsername);
        webhookSecret = normalize(webhookSecret);
        linkTokenPepper = normalize(linkTokenPepper);
        if (maxAttempts <= 0) {
            maxAttempts = 10;
        }
        if (maxListedMembers <= 0) {
            maxListedMembers = 30;
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
