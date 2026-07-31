package com.example.attend.attendance.domain;

/**
 * 한 출석 대상 날짜에 저장되는 교사의 최종 상위 상태다.
 */
public enum AttendanceStatus {
	/** 정상 출석 구간에 들어온 기록이다. */
	PRESENT,
	/** 정책의 지각 구간 중 하나에 들어온 기록이다. */
	LATE,
	/** 마감 또는 관리자 정정으로 확정된 결석이다. */
	ABSENT
}
