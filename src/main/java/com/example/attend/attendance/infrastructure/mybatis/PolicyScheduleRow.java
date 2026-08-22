package com.example.attend.attendance.infrastructure.mybatis;

import com.example.attend.attendance.application.AttendanceDayRecurrence;

import java.time.LocalDate;

/** 잠긴 출석 정책 일정의 상태 전이·출석일 생성용 행이다. */
public record PolicyScheduleRow(
        long id,
        long departmentId,
        long policyVersionId,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        AttendanceDayRecurrence recurrence,
        int intervalValue,
        Integer yearlyMonth,
        Integer yearlyDay
) {
}
