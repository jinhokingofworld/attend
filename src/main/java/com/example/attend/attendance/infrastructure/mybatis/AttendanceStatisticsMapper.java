package com.example.attend.attendance.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * FINALIZED 대상 날짜만 분모로 사용하는 통계 Mapper다.
 */
@Mapper
public interface AttendanceStatisticsMapper {

	/** 교사의 기간별 상위 상태 건수를 계산한다. */
	AttendanceStatisticsSummaryRow selectMemberSummary(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("fromDate") LocalDate fromDate,
			@Param("toDate") LocalDate toDate);

	/** 교사의 기간별 지각 단계 snapshot 건수를 계산한다. */
	List<AttendanceBandCountRow> selectMemberLateBands(
			@Param("departmentId") long departmentId,
			@Param("memberId") long memberId,
			@Param("fromDate") LocalDate fromDate,
			@Param("toDate") LocalDate toDate);
}
