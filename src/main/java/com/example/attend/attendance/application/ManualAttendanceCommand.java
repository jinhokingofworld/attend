package com.example.attend.attendance.application;

import java.time.Instant;

/**
 * 관리자가 실제 출석 시각 또는 결석을 명시해 기록을 등록·정정하는 명령이다.
 *
 * @param actualCheckInAt 정상·지각 판정에 사용할 실제 시각. 결석이면 {@code null}
 * @param markAbsent 결석으로 명시적으로 정정하는지 여부
 * @param addMissingTarget 대상자 누락을 같은 트랜잭션에서 보완할지 여부
 * @param note 선택 비고
 * @param reason 필수 정정 사유
 */
public record ManualAttendanceCommand(
		Instant actualCheckInAt,
		boolean markAbsent,
		boolean addMissingTarget,
		String note,
		String reason
) {

	/**
	 * 실제 시각과 결석 선택이 모순되지 않게 하고 문자열 길이를 제한한다.
	 */
	public ManualAttendanceCommand {
		if (markAbsent == (actualCheckInAt != null)) {
			throw new IllegalArgumentException(
					"absence must have no check-in time and arrival must have one");
		}
		if (markAbsent && addMissingTarget) {
			throw new IllegalArgumentException(
					"a missing target can only be added with an actual arrival");
		}
		if (note != null) {
			note = note.trim();
			if (note.isEmpty()) {
				note = null;
			} else if (note.length() > 1000) {
				throw new IllegalArgumentException("note must not exceed 1000 characters");
			}
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		reason = reason.trim();
		if (reason.length() > 500) {
			throw new IllegalArgumentException("reason must not exceed 500 characters");
		}
	}
}
