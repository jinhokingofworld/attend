package com.example.attend.attendance.infrastructure.mybatis;

import java.time.LocalTime;

/**
 * 잠긴 출석 정책 버전의 발행 검증용 행이다.
 *
 * @param id 정책 식별자
 * @param departmentId 소유 부서
 * @param name 정책 이름
 * @param checkInStartTime 태깅 시작 시각
 * @param status 정책 상태
 */
public record PolicyVersionRow(
		long id,
		long departmentId,
		String name,
		LocalTime checkInStartTime,
		String status
) {
}
