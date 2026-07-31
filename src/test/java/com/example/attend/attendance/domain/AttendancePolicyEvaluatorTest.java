package com.example.attend.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 출석 정책의 발행 조건과 시각 경계를 외부 의존성 없이 검증한다.
 */
class AttendancePolicyEvaluatorTest {

	private static final ZoneId ATTENDANCE_ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalDate ATTENDANCE_DATE = LocalDate.of(2026, 7, 31);

	private final AttendancePolicyEvaluator evaluator = new AttendancePolicyEvaluator();

	/**
	 * 시작·정상 상한·마지막 지각 상한의 정확한 경계가 문서 계약과 같은지 검증한다.
	 *
	 * <p>상한과 같은 시각은 해당 구간에 포함하고, 1나노초 뒤부터 다음 결과로 이동한다.</p>
	 */
	@Test
	void evaluatesInclusiveBoundariesUsingOneServerReceivedAt() {
		AttendanceBand present = band(
				101,
				1,
				"정상 출석",
				AttendanceParentStatus.PRESENT,
				LocalTime.of(9, 0));
		AttendanceBand late = band(
				102,
				2,
				"1차 지각",
				AttendanceParentStatus.LATE,
				LocalTime.of(9, 15));
		AttendancePolicy policy = new AttendancePolicy(
				11,
				LocalTime.of(8, 30),
				List.of(present, late));

		assertThat(evaluateAt(policy, LocalTime.of(8, 30).minusNanos(1)))
				.isInstanceOf(AttendanceDecision.CheckInNotOpen.class);
		assertMatchedBand(evaluateAt(policy, LocalTime.of(8, 30)), present);
		assertMatchedBand(evaluateAt(policy, LocalTime.of(9, 0)), present);
		assertMatchedBand(evaluateAt(policy, LocalTime.of(9, 0).plusNanos(1)), late);
		assertMatchedBand(evaluateAt(policy, LocalTime.of(9, 15)), late);
		assertThat(evaluateAt(policy, LocalTime.of(9, 15).plusNanos(1)))
				.isInstanceOf(AttendanceDecision.CheckInClosed.class);
	}

	/**
	 * 정상 구간 한 개와 지각 구간 한 개 이상, 순서와 오름차순 조건을 검증한다.
	 */
	@Test
	void rejectsPoliciesThatCannotBePublished() {
		AttendanceBand present = band(
				101,
				1,
				"정상 출석",
				AttendanceParentStatus.PRESENT,
				LocalTime.of(9, 0));
		AttendanceBand lateFirst = band(
				101,
				1,
				"1차 지각",
				AttendanceParentStatus.LATE,
				LocalTime.of(9, 0));
		AttendanceBand secondPresent = band(
				102,
				2,
				"두 번째 정상",
				AttendanceParentStatus.PRESENT,
				LocalTime.of(9, 15));
		AttendanceBand reversedLate = band(
				102,
				2,
				"자정을 넘긴 지각",
				AttendanceParentStatus.LATE,
				LocalTime.of(0, 15));

		assertThatThrownBy(() -> new AttendancePolicy(
				11,
				LocalTime.of(8, 30),
				List.of(present)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AttendancePolicy(
				11,
				LocalTime.of(8, 30),
				List.of(lateFirst, late(102, 2, LocalTime.of(9, 15)))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AttendancePolicy(
				11,
				LocalTime.of(8, 30),
				List.of(present, secondPresent)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AttendancePolicy(
				11,
				LocalTime.of(8, 30),
				List.of(present, reversedLate)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AttendancePolicy(
				11,
				LocalTime.of(9, 1),
				List.of(present, late(102, 2, LocalTime.of(9, 15)))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * 테스트 날짜의 현지 시각을 고정 Clock의 Instant로 바꾸어 판정한다.
	 *
	 * @param policy 평가할 정책
	 * @param receivedTime 테스트할 Asia/Seoul 현지 시각
	 * @return 정책 판정 결과
	 */
	private AttendanceDecision evaluateAt(
			AttendancePolicy policy,
			LocalTime receivedTime
	) {
		Instant receivedAt = ZonedDateTime.of(
				ATTENDANCE_DATE,
				receivedTime,
				ATTENDANCE_ZONE).toInstant();
		Clock requestClock = Clock.fixed(receivedAt, ATTENDANCE_ZONE);

		return evaluator.evaluate(policy, requestClock.instant(), requestClock.getZone());
	}

	/**
	 * 테스트용 구간을 읽기 쉬운 형태로 생성한다.
	 *
	 * @param id 구간 식별자
	 * @param sequenceNo 평가 순서
	 * @param label 표시 이름
	 * @param status 정상 또는 지각 상태
	 * @param upperTime 포함 상한
	 * @return 테스트 정책 구간
	 */
	private static AttendanceBand band(
			long id,
			int sequenceNo,
			String label,
			AttendanceParentStatus status,
			LocalTime upperTime
	) {
		return new AttendanceBand(id, sequenceNo, label, status, upperTime);
	}

	/**
	 * 반복되는 지각 구간 fixture를 생성한다.
	 *
	 * @param id 구간 식별자
	 * @param sequenceNo 평가 순서
	 * @param upperTime 포함 상한
	 * @return 지각 구간
	 */
	private static AttendanceBand late(long id, int sequenceNo, LocalTime upperTime) {
		return band(id, sequenceNo, "지각 " + sequenceNo, AttendanceParentStatus.LATE, upperTime);
	}

	/**
	 * 판정이 특정 구간과 일치했는지 타입과 값을 함께 확인한다.
	 *
	 * @param decision 실제 판정 결과
	 * @param expectedBand 예상 정책 구간
	 */
	private static void assertMatchedBand(
			AttendanceDecision decision,
			AttendanceBand expectedBand
	) {
		assertThat(decision)
				.isInstanceOfSatisfying(
						AttendanceDecision.Matched.class,
						matched -> assertThat(matched.band()).isEqualTo(expectedBand));
	}
}
