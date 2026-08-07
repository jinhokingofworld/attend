package com.example.attend.attendance.infrastructure.mybatis;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;

/**
 * 출석일 상태 변경과 시간 경계 검사에 필요한 잠긴 행이다.
 *
 * @param id 출석일 식별자
 * @param departmentId 소유 부서
 * @param attendanceDate 업무 날짜
 * @param policyVersionId 고정 정책 버전
 * @param status 저장 상태
 * @param checkInStartTime 고정 정책의 태깅 시작 시각
 */
public record AttendanceDayRow(
		long id,
		long departmentId,
		LocalDate attendanceDate,
		long policyVersionId,
		String status,
		LocalTime checkInStartTime,
		Instant finalizationDueAt
) {
}
