package com.example.attend.attendance.application;

import com.example.attend.attendance.domain.AttendanceParentStatus;

import java.time.LocalTime;
import java.util.Objects;

/**
 * 정책 초안에 저장할 한 개 구간 입력이다.
 *
 * @param sequenceNo 1부터 시작하는 표시·평가 순서
 * @param label 화면과 기록에 남길 이름
 * @param parentStatus 정상 또는 지각 상태
 * @param upperTime 포함 상한 시각
 */
public record PolicyBandInput(
		int sequenceNo,
		String label,
		AttendanceParentStatus parentStatus,
		LocalTime upperTime
) {

	/**
	 * 한 행 단위로 DB에 저장 가능한 값인지 검증한다.
	 */
	public PolicyBandInput {
		if (sequenceNo <= 0) {
			throw new IllegalArgumentException("band sequenceNo must be positive");
		}
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("band label must not be blank");
		}
		label = label.trim();
		if (label.length() > 50) {
			throw new IllegalArgumentException("band label must not exceed 50 characters");
		}
		Objects.requireNonNull(parentStatus, "band parentStatus must not be null");
		Objects.requireNonNull(upperTime, "band upperTime must not be null");
	}
}
