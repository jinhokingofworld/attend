package com.example.attend.access.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 부서 관리자 화면에 필요한 읽기 모델을 항상 부서 ID로 제한해 조회한다.
 */
@Mapper
public interface DepartmentAdminQueryMapper {

	/** 활성 부서 기본정보를 조회한다. */
	Map<String, Object> selectDepartment(
			@Param("departmentId") long departmentId);

	/** 지정 날짜의 출석 집계를 조회한다. */
	Map<String, Object> selectDashboard(
			@Param("departmentId") long departmentId,
			@Param("today") LocalDate today);

	/**
	 * 활성 교사와 현재 카드 요약을 조회한다.
	 *
	 * @param departmentId 부서 식별자
	 * @param today 나이 계산 기준일
	 * @return 이름순 교사 화면 행
	 */
	List<Map<String, Object>> selectTeachers(
			@Param("departmentId") long departmentId,
			@Param("today") LocalDate today);

	/**
	 * 활성 소속으로 범위를 제한한 교사 상세정보를 조회한다.
	 *
	 * @param departmentId 부서 식별자
	 * @param memberId 교사 식별자
	 * @param today 나이 계산 기준일
	 * @return 교사 상세 행, 없으면 {@code null}
	 */
	Map<String, Object> selectTeacher(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("today") LocalDate today);

	/**
	 * 교사의 최근 출석 대상·판정 이력을 조회한다.
	 *
	 * @param departmentId 부서 식별자
	 * @param memberId 교사 식별자
	 * @return 최신순 출석 이력
	 */
	List<Map<String, Object>> selectTeacherAttendanceHistory(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId);

	/** 부서 제외 확인 화면에 표시할 시작 전·기록 없는 대상 날짜를 조회한다. */
	List<Map<String, Object>> selectFutureAttendanceTargets(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("today") LocalDate today,
			@Param("currentTime") LocalTime currentTime);

	/** 정책 버전 목록을 조회한다. */
	List<Map<String, Object>> selectPolicies(
			@Param("departmentId") long departmentId);

	/** 알람처럼 독립적으로 관리하는 정책 일정 목록을 조회한다. */
	List<Map<String, Object>> selectPolicySchedules(
			@Param("departmentId") long departmentId);

	/** 편집 화면의 한 정책 일정과 현재 정책 버전을 조회한다. */
	Map<String, Object> selectPolicySchedule(
			@Param("departmentId") long departmentId,
			@Param("scheduleId") long scheduleId);

	List<Integer> selectPolicyScheduleWeekdays(@Param("scheduleId") long scheduleId);

	List<Integer> selectPolicyScheduleMonthdays(@Param("scheduleId") long scheduleId);

	/** 한 정책 버전을 조회한다. */
	Map<String, Object> selectPolicy(
			@Param("departmentId") long departmentId,
			@Param("policyId") long policyId);

	/** 한 정책의 순서화된 단계 목록을 조회한다. */
	List<Map<String, Object>> selectPolicyBands(
			@Param("departmentId") long departmentId,
			@Param("policyId") long policyId);

	/** 발행된 정책 목록을 조회한다. */
	List<Map<String, Object>> selectPublishedPolicies(
			@Param("departmentId") long departmentId);

	/** 출석 날짜 목록과 대상·기록 수를 조회한다. */
	List<Map<String, Object>> selectAttendanceDays(
			@Param("departmentId") long departmentId);

	/** 한 출석 날짜와 고정 정책을 조회한다. */
	Map<String, Object> selectAttendanceDay(
			@Param("departmentId") long departmentId,
			@Param("attendanceDayId") long attendanceDayId);

	/** 날짜의 대상자·결과 행을 조회한다. */
	List<Map<String, Object>> selectAttendanceRows(
			@Param("departmentId") long departmentId,
			@Param("attendanceDayId") long attendanceDayId);

	/** 부서 범위 감사 이력을 조회한다. */
	List<Map<String, Object>> selectHistory(
			@Param("departmentId") long departmentId);

	/** 부서 범위 태깅 이력을 조회한다. */
	List<Map<String, Object>> selectTagHistory(
			@Param("departmentId") long departmentId);

	/** 미등록·비활성 카드 이벤트를 UID별 최근 한 건으로 조회한다. */
	List<Map<String, Object>> selectCardInbox(
			@Param("departmentId") long departmentId,
			@Param("receivedSince") Instant receivedSince,
			@Param("limit") int limit);
}
