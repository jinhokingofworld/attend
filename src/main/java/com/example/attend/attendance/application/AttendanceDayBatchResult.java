package com.example.attend.attendance.application;

/** 반복 생성 요청에서 새로 만든 날짜와 기존 날짜 수를 반환한다. */
public record AttendanceDayBatchResult(
		int createdCount,
		int skippedExistingCount
) {

	public AttendanceDayBatchResult {
		if (createdCount < 0 || skippedExistingCount < 0) {
			throw new IllegalArgumentException("attendance day counts must not be negative");
		}
	}
}
