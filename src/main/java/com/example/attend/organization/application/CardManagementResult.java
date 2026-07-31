package com.example.attend.organization.application;

import com.example.attend.organization.domain.CardStatus;

/**
 * 카드 연결 명령의 변경 결과다.
 *
 * @param cardId 새로 활성화하거나 종료한 카드
 * @param assignmentId 생성하거나 종료한 연결
 * @param status 처리 후 카드 상태
 */
public record CardManagementResult(
		long cardId,
		long assignmentId,
		CardStatus status
) {
}
