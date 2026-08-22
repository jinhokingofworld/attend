package com.example.attend.attendance.infrastructure.mybatis;

import com.example.attend.attendance.application.AttendanceFinalizationClaim;
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
			@Param("policyScheduleId") Long policyScheduleId,
			@Param("finalizationDueAt") Instant finalizationDueAt,
			@Param("actorAccountId") long actorAccountId);

	/** 같은 부서의 취소되지 않은 날짜 중 정책 일정과 충돌하는 첫 날짜를 찾는다. */
	LocalDate selectFirstActiveDateConflict(
			@Param("departmentId") long departmentId,
			@Param("attendanceDates") List<LocalDate> attendanceDates);

	/** 아직 시작하지 않은 일정 소속 출석일만 취소한다. */
	int cancelFutureScheduleDays(
			@Param("departmentId") long departmentId,
			@Param("policyScheduleId") long policyScheduleId,
			@Param("today") LocalDate today,
			@Param("actorAccountId") long actorAccountId,
			@Param("canceledAt") Instant canceledAt,
			@Param("reason") String reason);

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
			@Param("policyVersionId") long policyVersionId,
			@Param("finalizationDueAt") Instant finalizationDueAt);

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

	/** 마감 예정 시각이 지난 미마감 날짜 ID를 찾는다. */
	List<Long> selectDueScheduledDayIds(@Param("now") Instant now);

	/** due, retry와 만료 lease 중 가장 이른 다음 실행 시각을 찾는다. */
	Instant selectNextFinalizationActionAt(@Param("now") Instant now);

	/** 현재 실행할 수 있는 미마감 날짜 후보를 제한된 개수로 찾는다. */
	List<Long> selectReadyFinalizationDayIds(
			@Param("now") Instant now,
			@Param("limit") int limit);

	/** 후보 날짜 하나를 claim version과 lease로 원자적으로 선점한다. */
	AttendanceFinalizationClaim claimFinalizationDay(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("now") Instant now,
			@Param("leaseUntil") Instant leaseUntil);

	/** claim 세대를 확인하고 실패 횟수와 다음 backoff를 기록한다. */
	int markFinalizationFailure(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("claimVersion") long claimVersion,
			@Param("failureCount") int failureCount,
			@Param("nextAttemptAt") Instant nextAttemptAt,
			@Param("errorCode") String errorCode,
			@Param("failedAt") Instant failedAt);

	/** 기록 없는 활성 대상자를 결석으로 채운다. */
	int insertMissingAbsences(@Param("attendanceDayId") long attendanceDayId);

	/** SCHEDULED 날짜를 FINALIZED로 전환한다. */
	int finalizeDay(
			@Param("attendanceDayId") long attendanceDayId,
			@Param("finalizedAt") Instant finalizedAt);
}
