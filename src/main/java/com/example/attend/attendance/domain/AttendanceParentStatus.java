package com.example.attend.attendance.domain;

/**
 * 출석 정책 구간이 만드는 상위 출석 상태다.
 *
 * <p>세부 지각 단계는 enum을 늘리지 않고 {@link AttendanceBand}의 이름과 순서로 표현한다.
 * 따라서 1차 지각과 2차 지각은 모두 {@code LATE}이지만 서로 다른 구간으로 보존된다.</p>
 */
public enum AttendanceParentStatus {
	/**
	 * 정상 출석 구간이다.
	 */
	PRESENT,

	/**
	 * 관리자가 동적으로 추가할 수 있는 지각 구간이다.
	 */
	LATE
}
