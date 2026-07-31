package com.example.attend.organization.application;

/**
 * 교사 등록 트랜잭션에서 만들어진 업무 식별자다.
 *
 * @param memberId 교사 식별자
 * @param membershipId 활성 소속 식별자
 * @param cardId 연결한 카드가 없으면 {@code null}
 * @param assignmentId 연결한 카드가 없으면 {@code null}
 */
public record TeacherRegistrationResult(
		long memberId,
		long membershipId,
		Long cardId,
		Long assignmentId
) {
}
