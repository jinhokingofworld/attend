package com.example.attend.device.infrastructure.mybatis;

/**
 * 날짜 잠금 전에 카드 실패 유형을 구분하는 비잠금 예비 projection이다.
 *
 * @param cardId NFC 카드 식별자
 * @param cardStatus 카드 현재 상태
 * @param assignmentDepartmentId 활성 연결 부서, 없으면 {@code null}
 */
public record DeviceCardCandidateRow(
		long cardId,
		String cardStatus,
		Long assignmentDepartmentId) {
}
