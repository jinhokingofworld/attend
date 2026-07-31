package com.example.attend.device.infrastructure.mybatis;

import java.time.LocalTime;

/**
 * 서버 날짜에 해당하는 잠긴 출석일과 고정 정책의 시작 시각이다.
 *
 * @param id 출석일 식별자
 * @param policyVersionId 고정 정책 버전 식별자
 * @param status 출석일 상태
 * @param checkInStartTime 태깅 시작 시각
 */
public record DeviceAttendanceDayRow(
		long id,
		long policyVersionId,
		String status,
		LocalTime checkInStartTime) {
}
