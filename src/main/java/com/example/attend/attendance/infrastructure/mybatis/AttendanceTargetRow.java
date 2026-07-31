package com.example.attend.attendance.infrastructure.mybatis;

import java.time.Instant;

/**
 * 잠긴 날짜 대상자와 해당 소속 기간이다.
 *
 * @param membershipId snapshot이 참조한 소속
 * @param isTarget 현재 공식 대상 여부
 * @param joinedAt 소속 시작 시각
 * @param endedAt 소속 종료 시각, 활성 소속이면 {@code null}
 */
public record AttendanceTargetRow(
		long membershipId,
		boolean isTarget,
		Instant joinedAt,
		Instant endedAt
) {
}
