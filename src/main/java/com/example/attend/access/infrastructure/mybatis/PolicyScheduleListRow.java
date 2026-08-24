package com.example.attend.access.infrastructure.mybatis;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 부서 정책 일정 목록 화면의 고정된 조회 계약이다.
 *
 * @param id 정책 일정 식별자
 * @param policyVersionId 연결된 정책 버전 식별자
 * @param name 정책 이름
 * @param status 정책 일정 상태
 * @param startDate 적용 시작일
 * @param endDate 적용 종료일, 계속 적용이면 {@code null}
 * @param recurrence 반복 방식
 * @param intervalValue 반복 간격
 * @param checkInStartTime 태깅 시작 시각
 * @param finalUpperTime 마지막 태깅 허용 시각
 */
public record PolicyScheduleListRow(
		long id,
		long policyVersionId,
		String name,
		String status,
		LocalDate startDate,
		LocalDate endDate,
		String recurrence,
		int intervalValue,
		LocalTime checkInStartTime,
		LocalTime finalUpperTime) {
}
