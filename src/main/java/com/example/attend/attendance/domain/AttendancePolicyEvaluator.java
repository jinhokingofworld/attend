package com.example.attend.attendance.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 한 번 캡처한 서버 수신 시각을 검증된 출석 정책에 적용한다.
 *
 * <p>장치가 보낸 시각은 신뢰하지 않는다. application service가 주입된
 * {@link java.time.Clock}으로 {@code receivedAt}을 한 번 얻고, 그 값을 이벤트 저장과
 * 이 판정에 함께 사용해야 한다.</p>
 */
public final class AttendancePolicyEvaluator {

	/**
	 * 수신 시각이 시작 전인지, 어느 구간인지, 마감 후인지 판정한다.
	 *
	 * @param policy 생성 시 발행 규칙 검증을 마친 정책
	 * @param receivedAt 서버가 태깅 요청마다 한 번 캡처한 수신 시각
	 * @param attendanceZone 출석 업무 기준 시간대
	 * @return 시작 전, 일치한 구간 또는 마감 후 결과
	 */
	public AttendanceDecision evaluate(
			AttendancePolicy policy,
			Instant receivedAt,
			ZoneId attendanceZone
	) {
		Objects.requireNonNull(policy, "policy must not be null");
		Objects.requireNonNull(receivedAt, "receivedAt must not be null");
		Objects.requireNonNull(attendanceZone, "attendanceZone must not be null");

		LocalTime receivedTime = LocalTime.ofInstant(receivedAt, attendanceZone);
		if (receivedTime.isBefore(policy.checkInStartTime())) {
			return new AttendanceDecision.CheckInNotOpen();
		}

		for (AttendanceBand band : policy.bands()) {
			if (!receivedTime.isAfter(band.upperTime())) {
				return new AttendanceDecision.Matched(band);
			}
		}

		return new AttendanceDecision.CheckInClosed();
	}
}
