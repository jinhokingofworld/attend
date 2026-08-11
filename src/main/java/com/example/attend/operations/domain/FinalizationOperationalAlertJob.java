package com.example.attend.operations.domain;

import java.time.Instant;
import java.time.LocalDate;

/** 운영 Telegram Bot으로 전달하도록 claim된 마감 재시도 소진 이벤트다. */
public record FinalizationOperationalAlertJob(
        long id,
        String eventType,
        long attendanceDayId,
        long incidentClaimVersion,
        long departmentId,
        String departmentName,
        LocalDate attendanceDate,
        Instant firstFailedAt,
        Instant occurredAt,
        int totalAttemptCount,
        String errorCode,
        int deliveryAttemptCount,
        long deliveryClaimVersion) {
}
