package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceStatisticsMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceStatisticsSummaryRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 인증된 부서 범위에서 교사의 공식 출석 통계를 조회한다.
 */
@Service
public class AttendanceStatisticsService {

	private final DepartmentAuthorization authorization;
	private final AttendanceStatisticsMapper mapper;

	/**
	 * 인가 경계와 통계 Mapper를 주입받는다.
	 */
	public AttendanceStatisticsService(
			DepartmentAuthorization authorization,
			AttendanceStatisticsMapper mapper
	) {
		this.authorization = authorization;
		this.mapper = mapper;
	}

	/**
	 * 시작일과 종료일을 포함하는 기간의 교사 통계를 계산한다.
	 */
	@Transactional(readOnly = true)
	public AttendanceStatistics getMemberStatistics(
			AccountActor actor,
			long departmentId,
			long memberId,
			LocalDate fromDate,
			LocalDate toDate
	) {
		if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
			throw new IllegalArgumentException("statistics date range is invalid");
		}
		authorization.requireDepartmentAdmin(actor, departmentId);
		AttendanceStatisticsSummaryRow summary = mapper.selectMemberSummary(
				departmentId,
				memberId,
				fromDate,
				toDate);
		return new AttendanceStatistics(
				summary.totalCount(),
				summary.presentCount(),
				summary.lateCount(),
				summary.absentCount(),
				List.copyOf(mapper.selectMemberLateBands(
						departmentId,
						memberId,
						fromDate,
						toDate)));
	}
}
