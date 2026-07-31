package com.example.attend.attendance.application;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * 출석 정책 초안의 이름, 시작 시각과 편집 중인 구간 목록이다.
 *
 * @param name 관리자가 구분할 정책 버전 이름
 * @param checkInStartTime NFC 태깅 시작 시각
 * @param bands 저장할 구간 목록. 초안은 비어 있을 수 있음
 */
public record PolicyDraftCommand(
		String name,
		LocalTime checkInStartTime,
		List<PolicyBandInput> bands
) {

	/**
	 * 초안 저장 형식을 검증하고 목록을 불변 복사한다.
	 *
	 * <p>첫 구간 상태와 상한 오름차순 같은 발행 규칙은 편집 중 초안에는 강제하지 않고
	 * 발행 명령에서 전체 검증한다.</p>
	 */
	public PolicyDraftCommand {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("policy name must not be blank");
		}
		name = name.trim();
		if (name.length() > 100) {
			throw new IllegalArgumentException("policy name must not exceed 100 characters");
		}
		Objects.requireNonNull(checkInStartTime, "checkInStartTime must not be null");
		Objects.requireNonNull(bands, "bands must not be null");
		bands = List.copyOf(bands);
	}
}
