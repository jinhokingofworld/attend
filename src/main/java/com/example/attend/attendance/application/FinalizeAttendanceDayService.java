package com.example.attend.attendance.application;

import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.DepartmentLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 과거 SCHEDULED 날짜의 누락 기록을 결석으로 채우고 날짜를 마감한다.
 */
@Service
public class FinalizeAttendanceDayService {

	private final AttendanceDayMapper dayMapper;
	private final DepartmentLock departmentLock;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;

	/**
	 * 자동 마감의 저장·잠금·시간 경계를 주입받는다.
	 */
	public FinalizeAttendanceDayService(
			AttendanceDayMapper dayMapper,
			DepartmentLock departmentLock,
			AuditLogWriter auditLogWriter,
			Clock clock
	) {
		this.dayMapper = dayMapper;
		this.departmentLock = departmentLock;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
	}

	/**
	 * 재기동 catch-up 대상인 모든 과거 미마감 날짜를 조회한다.
	 *
	 * @return 날짜·ID 오름차순의 출석일 ID
	 */
	@Transactional(readOnly = true)
	public List<Long> findPendingDayIds() {
		return dayMapper.selectPastScheduledDayIds(LocalDate.now(clock));
	}

	/**
	 * 한 날짜를 독립 트랜잭션으로 멱등 마감한다.
	 *
	 * @param attendanceDayId 마감할 날짜 식별자
	 * @return 새로 생성한 결석 수
	 */
	@Transactional
	public int finalizeDay(long attendanceDayId) {
		Long departmentId = dayMapper.selectDepartmentId(attendanceDayId);
		if (departmentId == null) {
			throw new ResourceNotFoundException("attendance day");
		}
		departmentLock.lockActive(departmentId);
		AttendanceDayRow day = dayMapper.lockDay(departmentId, attendanceDayId);
		if (day == null) {
			throw new ResourceNotFoundException("attendance day");
		}
		if (!"SCHEDULED".equals(day.status())) {
			return 0;
		}
		if (!day.attendanceDate().isBefore(LocalDate.now(clock))) {
			throw new BusinessRuleException("only a past attendance day can be finalized");
		}

		int absenceCount = dayMapper.insertMissingAbsences(attendanceDayId);
		if (dayMapper.finalizeDay(attendanceDayId, clock.instant()) != 1) {
			throw new BusinessRuleException("attendance day changed concurrently");
		}
		int auditRows = auditLogWriter.writeSystemOnce(
				departmentId,
				attendanceDayId,
				"ATTENDANCE_DAY_FINALIZED",
				"ATTENDANCE_DAY",
				Long.toString(attendanceDayId),
				Map.of("status", "FINALIZED", "absenceCount", absenceCount),
				"attendance-day:" + attendanceDayId + ":finalize");
		if (auditRows != 1) {
			throw new BusinessRuleException("finalization audit already exists");
		}
		return absenceCount;
	}
}
