package com.example.attend.attendance.application;

import com.example.attend.organization.domain.CardDisposition;

import java.util.List;
import java.util.Objects;

/**
 * 교사를 부서에서 제외하면서 선택한 미래 대상자도 제외하는 명령이다.
 *
 * @param futureAttendanceDayIds 태깅 시작 전 제외할 대상 날짜 ID
 * @param cardDisposition 활성 카드가 있을 때 적용할 상태
 * @param reason 필수 업무 사유
 */
public record ExcludeTeacherCommand(
		List<Long> futureAttendanceDayIds,
		CardDisposition cardDisposition,
		String reason
) {

	/**
	 * 날짜를 중복 없는 오름차순으로 고정하고 사유를 검증한다.
	 */
	public ExcludeTeacherCommand {
		Objects.requireNonNull(futureAttendanceDayIds, "futureAttendanceDayIds must not be null");
		futureAttendanceDayIds = futureAttendanceDayIds.stream()
				.peek(id -> {
					if (id == null || id <= 0) {
						throw new IllegalArgumentException(
								"future attendance day id must be positive");
					}
				})
				.distinct()
				.sorted()
				.toList();
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
