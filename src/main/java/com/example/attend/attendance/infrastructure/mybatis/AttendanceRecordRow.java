package com.example.attend.attendance.infrastructure.mybatis;

import java.time.Instant;

/**
 * 수동 정정 전에 잠근 기존 출석 기록의 감사 snapshot이다.
 *
 * @param id 기록 식별자
 * @param status 기존 상위 상태
 * @param attendanceBandId 기존 구간, 결석이면 {@code null}
 * @param bandLabelSnapshot 기존 단계명, 결석이면 {@code null}
 * @param checkedInAt 기존 실제 출석 시각, 결석이면 {@code null}
 * @param source 기존 판정 원천
 * @param note 기존 비고
 */
public record AttendanceRecordRow(
		long id,
		String status,
		Long attendanceBandId,
		String bandLabelSnapshot,
		Instant checkedInAt,
		String source,
		String note
) {
}
