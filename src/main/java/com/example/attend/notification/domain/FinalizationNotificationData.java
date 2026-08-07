package com.example.attend.notification.domain;

import java.time.LocalDate;

/** 출석 마감 시점에 Telegram 본문으로 고정할 집계다. */
public record FinalizationNotificationData(
		long attendanceDayId,
        long departmentId,
        LocalDate attendanceDate,
        String departmentName,
        int targetCount,
        int presentCount,
        int lateCount,
        int absentCount) {
}
