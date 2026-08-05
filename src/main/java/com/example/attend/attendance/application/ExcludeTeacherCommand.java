package com.example.attend.attendance.application;

import com.example.attend.organization.domain.CardDisposition;

import java.util.Objects;

/**
 * 교사를 부서에서 제외하면서 확인한 미래 대상자를 자동 제외하는 명령이다.
 *
 * @param expectedFutureAttendanceDayCount 관리자가 확인한 시작 전 대상 날짜 건수
 * @param cardDisposition 활성 카드가 있을 때 적용할 상태
 * @param reason 필수 업무 사유
 */
public record ExcludeTeacherCommand(
		int expectedFutureAttendanceDayCount,
		CardDisposition cardDisposition,
		String reason
) {

	/**
	 * 확인 건수와 카드 처리, 사유를 검증한다.
	 */
	public ExcludeTeacherCommand {
		if (expectedFutureAttendanceDayCount < 0) {
			throw new IllegalArgumentException(
					"expected future attendance day count must not be negative");
		}
		Objects.requireNonNull(cardDisposition, "cardDisposition must not be null");
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		reason = reason.trim();
		if (reason.length() > 500) {
			throw new IllegalArgumentException("reason must not exceed 500 characters");
		}
	}
}
