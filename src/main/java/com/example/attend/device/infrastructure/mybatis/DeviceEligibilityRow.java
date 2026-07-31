package com.example.attend.device.infrastructure.mybatis;

/**
 * 잠금 뒤 다시 확인한 카드·연결·활성 소속의 출석 자격이다.
 *
 * @param cardId NFC 카드 식별자
 * @param membershipId 활성 부서 소속 식별자
 * @param memberId 출석 대상 교사 식별자
 */
public record DeviceEligibilityRow(
		long cardId,
		long membershipId,
		long memberId) {
}
