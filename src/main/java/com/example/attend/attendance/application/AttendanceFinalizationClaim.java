package com.example.attend.attendance.application;

/** 다중 인스턴스에서 선점 세대를 식별하는 출석일 마감 작업이다. */
public record AttendanceFinalizationClaim(
		long attendanceDayId,
		int failureCount,
		long claimVersion
) {
}
