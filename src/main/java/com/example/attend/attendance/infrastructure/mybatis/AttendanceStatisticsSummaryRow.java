package com.example.attend.attendance.infrastructure.mybatis;

/**
 * 같은 FINALIZED 대상 날짜 분모로 계산한 출석 상태 건수다.
 *
 * @param totalCount 공식 대상 날짜 수
 * @param presentCount 정상 출석 수
 * @param lateCount 전체 지각 수
 * @param absentCount 결석 수
 */
public record AttendanceStatisticsSummaryRow(
		int totalCount,
		int presentCount,
		int lateCount,
		int absentCount
) {
}
