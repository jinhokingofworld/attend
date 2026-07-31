package com.example.attend.attendance.domain;

import java.time.LocalTime;
import java.util.Objects;

/**
 * 발행할 출석 정책 안의 한 개 시간 구간이다.
 *
 * <p>{@code upperTime}은 포함 경계다. 예를 들어 정상 출석 상한이 09:00이면
 * 09:00:00에 수신한 태깅까지 이 구간에 포함되고 그 직후부터 다음 구간을 평가한다.</p>
 *
 * @param id 구간의 DB 식별자
 * @param sequenceNo 정책 안에서의 1부터 시작하는 평가 순서
 * @param label 관리자 화면과 출석 기록에 보존할 구간 이름
 * @param parentStatus 정상 출석 또는 지각 상태
 * @param upperTime 이 구간에 포함되는 마지막 시각
 */
public record AttendanceBand(
		long id,
		int sequenceNo,
		String label,
		AttendanceParentStatus parentStatus,
		LocalTime upperTime
) {

	/**
	 * 한 구간만으로 검증할 수 있는 기본 불변식을 확인한다.
	 *
	 * <p>정상 구간의 위치와 구간 간 시각 순서는 여러 행을 함께 봐야 하므로
	 * {@link AttendancePolicy}가 검증한다.</p>
	 */
	public AttendanceBand {
		if (id <= 0) {
			throw new IllegalArgumentException("band id must be positive");
		}
		if (sequenceNo <= 0) {
			throw new IllegalArgumentException("band sequenceNo must be positive");
		}
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("band label must not be blank");
		}
		Objects.requireNonNull(parentStatus, "band parentStatus must not be null");
		Objects.requireNonNull(upperTime, "band upperTime must not be null");
	}
}
