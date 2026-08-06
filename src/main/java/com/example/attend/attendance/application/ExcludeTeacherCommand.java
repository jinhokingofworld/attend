package com.example.attend.attendance.application;

import com.example.attend.organization.domain.CardDisposition;

import java.util.Objects;
import java.util.Set;

/**
 * 교사를 부서에서 제외하면서 확인한 미래 대상자를 자동 제외하는 명령이다.
 *
 * @param expectedFutureAttendanceDayIds 관리자가 확인한 시작 전 대상 날짜 식별자 집합
 * @param cardDisposition 활성 카드가 있을 때 적용할 상태
 * @param reason 필수 업무 사유
 */
public record ExcludeTeacherCommand(
		Set<Long> expectedFutureAttendanceDayIds,
		CardDisposition cardDisposition,
		String reason
) {

	/**
	 * 확인한 날짜 집합과 카드 처리, 사유를 검증한다.
	 */
	public ExcludeTeacherCommand {
		Objects.requireNonNull(
				expectedFutureAttendanceDayIds,
				"expected future attendance day ids must not be null");
		if (expectedFutureAttendanceDayIds.stream()
				.anyMatch(id -> id == null || id <= 0)) {
			throw new IllegalArgumentException(
					"expected future attendance day ids must be positive");
		}
		expectedFutureAttendanceDayIds = Set.copyOf(expectedFutureAttendanceDayIds);
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
