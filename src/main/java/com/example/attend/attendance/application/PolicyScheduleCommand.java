package com.example.attend.attendance.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** 관리자 화면의 한 출석 정책과 적용 반복 조건이다. */
public record PolicyScheduleCommand(
        PolicyDraftCommand policy,
        LocalDate startDate,
        LocalDate endDate,
        AttendanceDayRecurrence recurrence,
        int interval,
        Set<DayOfWeek> weeklyDays,
        Set<Integer> monthlyDays,
        Integer yearlyMonth,
        Integer yearlyDay,
        boolean enabled
) {
    public PolicyScheduleCommand {
        if (startDate == null) {
            throw new IllegalArgumentException("정책 적용 시작일을 입력하세요.");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("정책 종료일은 시작일보다 빠를 수 없습니다.");
        }
        if (recurrence == null) {
            throw new IllegalArgumentException("반복 방식을 선택하세요.");
        }
        if (recurrence == AttendanceDayRecurrence.NONE && endDate != null
                && !endDate.equals(startDate)) {
            throw new IllegalArgumentException("반복하지 않는 정책은 적용 날짜를 하나만 가집니다.");
        }
        policy = java.util.Objects.requireNonNull(policy, "policy must not be null");
        weeklyDays = weeklyDays == null ? Set.of() : Set.copyOf(weeklyDays);
        monthlyDays = monthlyDays == null ? Set.of() : Set.copyOf(monthlyDays);
        LocalDate validationEnd = recurrence == AttendanceDayRecurrence.NONE
                ? startDate
                : endDate == null ? startDate.plusYears(5) : endDate;
        new AttendanceDayScheduleCommand(
                startDate, validationEnd, 1L, recurrence, interval,
                weeklyDays, monthlyDays, yearlyMonth, yearlyDay);
    }

    /** 정책의 시작 규칙을 유지한 채 지정 범위에 발생하는 날짜를 계산한다. */
    public java.util.List<LocalDate> occurrenceDatesUntil(LocalDate horizonEnd) {
        LocalDate effectiveEnd = endDate == null
                ? horizonEnd
                : endDate.isBefore(horizonEnd) ? endDate : horizonEnd;
        if (effectiveEnd.isBefore(startDate)) {
            return java.util.List.of();
        }
        return new AttendanceDayScheduleCommand(
                startDate, effectiveEnd, 1L, recurrence, interval,
                weeklyDays, monthlyDays, yearlyMonth, yearlyDay).occurrenceDates();
    }
}
