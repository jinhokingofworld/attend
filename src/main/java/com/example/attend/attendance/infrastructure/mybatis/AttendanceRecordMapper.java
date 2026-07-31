package com.example.attend.attendance.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * 수동 출석 대상과 최종 기록을 잠그고 저장하는 Mapper다.
 */
@Mapper
public interface AttendanceRecordMapper {

	/** 날짜·교사의 대상자와 소속을 잠근다. */
	AttendanceTargetRow lockTarget(
			@Param("departmentId") long departmentId,
			@Param("attendanceDayId") long attendanceDayId,
			@Param("memberId") long memberId);

	/** 실제 출석 시각을 포함하는 부서 소속 이력을 잠근다. */
	MembershipPeriodRow lockMembershipAt(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("actualCheckInAt") Instant actualCheckInAt);

	/** 누락자를 MANUAL 대상자로 추가한다. */
	int insertManualTarget(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("membershipId") long membershipId,
			@Param("actorAccountId") long actorAccountId,
			@Param("changedAt") Instant changedAt,
			@Param("reason") String reason);

	/** 비활성화된 기존 대상자를 명시적 수동 대상으로 다시 활성화한다. */
	int reactivateManualTarget(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("membershipId") long membershipId,
			@Param("actorAccountId") long actorAccountId,
			@Param("changedAt") Instant changedAt,
			@Param("reason") String reason);

	/** 기존 최종 기록을 잠근다. */
	AttendanceRecordRow lockRecord(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("memberId") long memberId);

	/** 관리자가 계산된 최종 기록을 새로 저장하고 ID를 반환한다. */
	long insertManualRecord(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("policyVersionId") long policyVersionId,
			@Param("memberId") long memberId,
			@Param("attendanceBandId") Long attendanceBandId,
			@Param("status") String status,
			@Param("bandSequence") Integer bandSequence,
			@Param("bandLabel") String bandLabel,
			@Param("checkedInAt") Instant checkedInAt,
			@Param("note") String note,
			@Param("actorAccountId") long actorAccountId);

	/** 기존 기록을 관리자 판정으로 정정한다. */
	int updateManualRecord(
			@Param("recordId") long recordId,
			@Param("policyVersionId") long policyVersionId,
			@Param("attendanceBandId") Long attendanceBandId,
			@Param("status") String status,
			@Param("bandSequence") Integer bandSequence,
			@Param("bandLabel") String bandLabel,
			@Param("checkedInAt") Instant checkedInAt,
			@Param("note") String note,
			@Param("actorAccountId") long actorAccountId);

	/** 판정 원천과 상태를 유지한 채 비고만 수정한다. */
	int updateNoteOnly(
			@Param("recordId") long recordId,
			@Param("note") String note,
			@Param("actorAccountId") long actorAccountId);
}
