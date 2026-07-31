package com.example.attend.attendance.domain;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 출석 판정에 필요한 한 버전의 정책을 나타낸다.
 *
 * <p>정책은 생성 시 발행 조건을 모두 검증한다. 그 결과 판정기는 잘못된 순서나 겹치는
 * 구간을 방어하는 조건문을 반복하지 않고, 검증이 끝난 구간을 앞에서부터 평가할 수 있다.</p>
 *
 * @param policyVersionId 정책 버전의 DB 식별자
 * @param checkInStartTime NFC 태깅을 받기 시작하는 시각
 * @param bands 평가 순서대로 정렬된 정상 출석 한 개와 지각 한 개 이상의 구간
 */
public record AttendancePolicy(
		long policyVersionId,
		LocalTime checkInStartTime,
		List<AttendanceBand> bands
) {

	/**
	 * 문서에 확정된 발행 규칙을 검증하고 외부에서 목록을 바꾸지 못하게 복사한다.
	 */
	public AttendancePolicy {
		if (policyVersionId <= 0) {
			throw new IllegalArgumentException("policyVersionId must be positive");
		}
		Objects.requireNonNull(checkInStartTime, "checkInStartTime must not be null");
		Objects.requireNonNull(bands, "bands must not be null");
		bands = List.copyOf(bands);

		if (bands.size() < 2) {
			throw new IllegalArgumentException(
					"policy must have one PRESENT band and at least one LATE band");
		}

		validateBands(checkInStartTime, bands);
	}

	/**
	 * 여러 구간을 함께 비교해야 알 수 있는 발행 조건을 검사한다.
	 *
	 * @param checkInStartTime 정책의 태깅 시작 시각
	 * @param bands 외부 변경이 차단된 평가 순서의 구간 목록
	 */
	private static void validateBands(
			LocalTime checkInStartTime,
			List<AttendanceBand> bands
	) {
		Set<Long> bandIds = new HashSet<>();
		LocalTime previousUpperTime = null;

		for (int index = 0; index < bands.size(); index++) {
			AttendanceBand band = Objects.requireNonNull(
					bands.get(index),
					"policy band must not be null");
			int expectedSequence = index + 1;

			if (band.sequenceNo() != expectedSequence) {
				throw new IllegalArgumentException(
						"band sequenceNo must be contiguous and start at 1");
			}
			if (!bandIds.add(band.id())) {
				throw new IllegalArgumentException("band id must be unique in a policy");
			}
			if (index == 0 && band.parentStatus() != AttendanceParentStatus.PRESENT) {
				throw new IllegalArgumentException("the first band must be PRESENT");
			}
			if (index > 0 && band.parentStatus() != AttendanceParentStatus.LATE) {
				throw new IllegalArgumentException("bands after the first must be LATE");
			}
			if (previousUpperTime != null
					&& !band.upperTime().isAfter(previousUpperTime)) {
				throw new IllegalArgumentException(
						"band upperTime values must be strictly increasing");
			}

			previousUpperTime = band.upperTime();
		}

		if (bands.getFirst().upperTime().isBefore(checkInStartTime)) {
			throw new IllegalArgumentException(
					"the first band upperTime must not be before checkInStartTime");
		}
	}
}
