package com.example.attend.attendance.application;

import com.example.attend.attendance.domain.AttendanceStatus;

/**
 * 서버가 고정 정책으로 계산해 저장한 수동 출석 결과다.
 *
 * @param attendanceRecordId 최종 기록 식별자
 * @param status 계산된 상위 상태
 * @param bandLabel 정상·지각 단계명, 결석이면 {@code null}
 * @param created 새 기록이면 {@code true}, 기존 기록 정정이면 {@code false}
 */
public record ManualAttendanceResult(
		long attendanceRecordId,
		AttendanceStatus status,
		String bandLabel,
		boolean created
) {
}
