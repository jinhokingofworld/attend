package com.example.attend.attendance.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 출석일, 대상자 snapshot과 날짜 상태를 저장하는 Mapper다.
 */
@Mapper
public interface AttendanceDayMapper {

	/** 같은 부서·날짜가 없을 때만 출석일을 만들고 식별자를 반환한다. */
	Long insertDayIfAbsent(
			@Param("departmentId") long departmentId,
			@Param("attendanceDate") LocalDate attendanceDate,
			@Param("policyVersionId") long policyVersionId,
			@Param("actorAccountId") long actorAccountId);

	/** 현재 활성 소속 교사를 날짜 대상자로 한 번에 snapshot한다. */
	int snapshotActiveMembers(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("departmentId") long departmentId);

	/** 승인된 부서의 출석일을 고정 정책과 함께 잠근다. */
	AttendanceDayRow lockDay(
			@Param("departmentId") long departmentId,
			@Param("attendanceDayId") long attendanceDayId);

	/** 출석일에 저장된 최종 기록 수를 계산한다. */
	int countRecords(@Param("attendanceDayId") long attendanceDayId);

	/** 기록 없는 SCHEDULED 날짜를 취소한다. */
	int cancelDay(
			@Param("departmentId") long departmentId,
			@Param("attendanceDayId") long attendanceDayId,
			@Param("actorAccountId") long actorAccountId,
			@Param("canceledAt") Instant canceledAt,
			@Param("reason") String reason);

	/** 시작 전인 기록 없는 날짜의 발행 정책을 교체한다. */
	int updateDayPolicy(
			@Param("departmentId") long departmentId,
			@Param("attendanceDayId") long attendanceDayId,
			@Param("policyVersionId") long policyVersionId);

	/** 시작 전이고 기록 없는 활성 대상 날짜 전체를 ID 순서로 잠근다. */
	List<AttendanceDayRow> lockFutureTargetDays(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("today") LocalDate today,
			@Param("currentTime") LocalTime currentTime);

	/** 일반 제외가 가능한 날짜의 대상자 상태를 비활성화한다. */
	int excludeTarget(
			@Param("departmentId") long departmentId,
			@Param("attendanceDayId") long attendanceDayId,
			@Param("memberId") long memberId,
			@Param("actorAccountId") long actorAccountId,
			@Param("changedAt") Instant changedAt,
			@Param("reason") String reason);

	/** 특정 날짜·교사의 기록 존재 여부를 확인한다. */
	int countMemberRecord(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("memberId") long memberId);

	/** 마감 service가 잠금 순서를 잡기 전에 날짜의 부서를 읽는다. */
	Long selectDepartmentId(@Param("attendanceDayId") long attendanceDayId);

	/** 현재 업무 날짜보다 이전인 미마감 날짜 ID를 찾는다. */
	List<Long> selectPastScheduledDayIds(@Param("today") LocalDate today);

	/** 기록 없는 활성 대상자를 결석으로 채운다. */
	int insertMissingAbsences(@Param("attendanceDayId") long attendanceDayId);

	/** SCHEDULED 날짜를 FINALIZED로 전환한다. */
	int finalizeDay(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("finalizedAt") Instant finalizedAt);
}
