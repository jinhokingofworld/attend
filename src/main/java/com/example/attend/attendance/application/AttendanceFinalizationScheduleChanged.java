package com.example.attend.attendance.application;

import java.time.Instant;

/** 새 출석일 또는 정책 교체로 동적 마감 예약을 앞당길 수 있음을 알린다. */
public record AttendanceFinalizationScheduleChanged(Instant finalizationDueAt) {
}
