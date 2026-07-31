package com.example.attend.organization.infrastructure.mybatis;

/**
 * 현재 활성 카드 연결과 카드 상태를 함께 조회한 행이다.
 *
 * @param assignmentId 연결 식별자
 * @param cardId 카드 식별자
 * @param cardStatus 현재 카드 상태 문자열
 */
public record CardAssignmentRow(
		long assignmentId,
		long cardId,
		String cardStatus
) {
}
