package com.example.attend.attendance.infrastructure.mybatis;

import com.example.attend.attendance.application.PolicyBandInput;
import com.example.attend.attendance.domain.AttendanceBand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * 출석 정책 초안·구간과 발행 상태를 저장하는 Mapper다.
 */
@Mapper
public interface AttendancePolicyMapper {

	/** 부서의 다음 정책 버전 번호를 계산한다. */
	int selectNextVersionNo(@Param("departmentId") long departmentId);

	/** 새 DRAFT 정책을 저장하고 식별자를 반환한다. */
	long insertDraft(
			@Param("departmentId") long departmentId,
			@Param("versionNo") int versionNo,
			@Param("name") String name,
			@Param("checkInStartTime") LocalTime checkInStartTime,
			@Param("actorAccountId") long actorAccountId);

	/** 초안에 한 구간을 저장한다. */
	void insertBand(
			@Param("policyVersionId") long policyVersionId,
			@Param("band") PolicyBandInput band);

	/** 승인된 부서의 초안 정책을 잠근다. */
	PolicyVersionRow lockDraft(
			@Param("departmentId") long departmentId,
			@Param("policyVersionId") long policyVersionId);

	/** DRAFT 상태일 때 이름과 시작 시각을 갱신한다. */
	int updateDraft(
			@Param("departmentId") long departmentId,
			@Param("policyVersionId") long policyVersionId,
			@Param("name") String name,
			@Param("checkInStartTime") LocalTime checkInStartTime);

	/** DRAFT 정책의 기존 구간을 모두 지운다. */
	int deleteDraftBands(@Param("policyVersionId") long policyVersionId);

	/** 정책 구간을 평가 순서로 조회한다. */
	List<AttendanceBand> selectBands(
			@Param("policyVersionId") long policyVersionId);

	/** 잠긴 초안을 한 번만 PUBLISHED로 전환한다. */
	int publish(
			@Param("departmentId") long departmentId,
			@Param("policyVersionId") long policyVersionId,
			@Param("actorAccountId") long actorAccountId,
			@Param("publishedAt") Instant publishedAt);

	/** 부서의 발행 정책을 조회한다. */
	PolicyVersionRow selectPublished(
			@Param("departmentId") long departmentId,
			@Param("policyVersionId") long policyVersionId);
}
