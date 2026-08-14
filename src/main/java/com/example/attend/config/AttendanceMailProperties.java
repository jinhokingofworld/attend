package com.example.attend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SMTP-based department administrator invitation delivery settings. */
@ConfigurationProperties(prefix = "attendance.mail")
public record AttendanceMailProperties(boolean enabled, String from, int maxAttempts) {

    public AttendanceMailProperties {
        from = from == null || from.isBlank() ? null : from.trim();
        if (maxAttempts < 1) {
            maxAttempts = 3;
        }
    }
}
