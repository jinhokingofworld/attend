package com.example.attend.organization.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * 교사·소속·NFC 카드와 연결 이력을 변경하는 조직 전용 Mapper다.
 */
@Mapper
public interface OrganizationMapper {

	/** 활성 부서 행을 잠그고 식별자를 반환한다. */
	Long lockActiveDepartment(@Param("departmentId") long departmentId);

	/**
	 * 신규 교사를 활성 상태로 저장한다.
	 *
	 * @param name 교사 이름
	 * @param phone 선택 연락처
	 * @param birth 선택 생년월일
	 * @return 생성된 교사 식별자
	 */
	long insertMember(
			@Param("name") String name,
			@Param("phone") String phone,
			@Param("birth") java.time.LocalDate birth);

	/** 교사의 신규 활성 소속을 저장하고 식별자를 반환한다. */
	long insertMembership(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("actorAccountId") long actorAccountId,
			@Param("joinedAt") Instant joinedAt);

	/**
	 * 활성 소속으로 범위를 제한해 교사의 허용된 기본정보만 수정한다.
	 *
	 * @param departmentId 부서 식별자
	 * @param memberId 교사 식별자
	 * @param name 변경할 이름
	 * @param phone 변경할 선택 연락처
	 * @param birth 변경할 선택 생년월일
	 * @return 수정된 행 수
	 */
	int updateTeacher(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("name") String name,
			@Param("phone") String phone,
			@Param("birth") java.time.LocalDate birth);

	/** 승인된 부서의 활성 소속을 잠근다. */
	MembershipRow lockActiveMembership(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId);

	/** 교사의 현재 활성 카드 연결을 카드 행과 함께 잠근다. */
	CardAssignmentRow lockActiveAssignment(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId);

	/** UID가 아직 없으면 연결 가능한 카드 행을 만든다. */
	int insertAvailableCardIfAbsent(@Param("uid") String uid);

	/** UID 카드 행을 잠근다. */
	CardRow lockCardByUid(@Param("uid") String uid);

	/**
	 * 부서의 미등록·비활성 태깅 이벤트에서 원본 UID를 서버 내부로만 읽는다.
	 *
	 * @param departmentId 부서 식별자
	 * @param eventId 태깅 이벤트 식별자
	 * @return 연결 가능한 원본 UID, 없으면 {@code null}
	 */
	String selectAssignableTagEventUid(
			@Param("departmentId") long departmentId,
			@Param("eventId") long eventId);

	/** 예상 상태일 때만 카드 상태를 변경한다. */
	int updateCardStatus(
			@Param("cardId") long cardId,
			@Param("expectedStatus") String expectedStatus,
			@Param("targetStatus") String targetStatus);

	/** 활성 카드 연결 이력을 새로 만든다. */
	long insertCardAssignment(
			@Param("cardId") long cardId,
			@Param("departmentId") long departmentId,
			@Param("membershipId") long membershipId,
			@Param("memberId") long memberId,
			@Param("actorAccountId") long actorAccountId,
			@Param("assignedAt") Instant assignedAt);

	/** 현재 활성 연결에 종료 metadata를 기록한다. */
	int endCardAssignment(
			@Param("assignmentId") long assignmentId,
			@Param("actorAccountId") long actorAccountId,
			@Param("endedAt") Instant endedAt,
			@Param("reason") String reason);

	/** 현재 활성 소속에 종료 metadata를 기록한다. */
	int endMembership(
			@Param("membershipId") long membershipId,
			@Param("actorAccountId") long actorAccountId,
			@Param("endedAt") Instant endedAt,
			@Param("reason") String reason);

	/** 다른 활성 소속이 없는 교사를 비활성화한다. */
	int deactivateMemberWithoutActiveMembership(@Param("memberId") long memberId);
}
