package com.example.attend.organization.api;

import com.example.attend.access.api.AccountActor;
import com.example.attend.organization.domain.CardDisposition;

import java.time.Instant;

/**
 * attendance 모듈의 부서 제외 orchestration이 호출하는 좁은 조직 command다.
 */
public interface MembershipClosure {

	/**
	 * 이미 부서·출석일 잠금을 획득한 트랜잭션에서 소속과 카드 연결을 종료한다.
	 *
	 * @param departmentId 승인되고 잠긴 부서
	 * @param memberId 제외할 교사
	 * @param actor 인증 관리자
	 * @param disposition 활성 카드가 있을 때 적용할 종료 상태
	 * @param reason 필수 제외 사유
	 * @param occurredAt 한 번 캡처한 처리 시각
	 * @return 종료한 소속과 카드 정보
	 */
	MembershipClosureResult close(
			long departmentId,
			long memberId,
			AccountActor actor,
			CardDisposition disposition,
			String reason,
			Instant occurredAt);
}
