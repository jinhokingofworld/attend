package com.example.attend.organization.api;

import com.example.attend.organization.domain.CardStatus;

/**
 * 소속 종료 트랜잭션에서 변경된 조직 데이터 식별자다.
 *
 * @param membershipId 종료된 소속
 * @param cardId 종료된 카드 연결이 없으면 {@code null}
 * @param cardStatus 종료 후 카드 상태가 없으면 {@code null}
 */
public record MembershipClosureResult(
		long membershipId,
		Long cardId,
		CardStatus cardStatus
) {
}
