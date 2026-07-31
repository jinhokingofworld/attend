package com.example.attend.attendance.domain;

import java.util.Objects;

/**
 * 서버 수신 시각을 출석 정책에 적용한 결과다.
 *
 * <p>시작 전과 마감 후는 출석 상태가 아니라 요청 거부 결과이므로 {@code PRESENT},
 * {@code LATE}, {@code ABSENT} 같은 상태 enum에 섞지 않는다.</p>
 */
public sealed interface AttendanceDecision {

	/**
	 * 아직 정책의 태깅 시작 시각이 되지 않았음을 나타낸다.
	 */
	record CheckInNotOpen() implements AttendanceDecision {
	}

	/**
	 * 정상 출석 또는 특정 지각 구간과 일치했음을 나타낸다.
	 *
	 * @param band 수신 시각과 일치한 정책 구간
	 */
	record Matched(AttendanceBand band) implements AttendanceDecision {

		/**
		 * 일치 결과는 항상 실제 정책 구간을 가져야 한다.
		 */
		public Matched {
			Objects.requireNonNull(band, "matched band must not be null");
		}
	}

	/**
	 * 마지막 지각 구간의 상한 시각도 지났음을 나타낸다.
	 */
	record CheckInClosed() implements AttendanceDecision {
	}
}
