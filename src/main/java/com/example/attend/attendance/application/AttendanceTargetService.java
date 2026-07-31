package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayRow;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceRecordMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceTargetRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.ActiveMembership;
import com.example.attend.organization.api.ActiveMembershipLookup;
import com.example.attend.organization.api.DepartmentLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * 태깅 시작 전 일반 출석 대상자 추가·제외를 이력 보존 방식으로 처리한다.
 */
@Service
public class AttendanceTargetService {

	private final DepartmentAuthorization authorization;
	private final DepartmentLock departmentLock;
	private final ActiveMembershipLookup membershipLookup;
	private final AttendanceDayMapper dayMapper;
	private final AttendanceRecordMapper recordMapper;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;
	private final ZoneId attendanceZone;

	/**
	 * 대상자 변경에 필요한 인가·잠금·저장 경계를 주입받는다.
	 */
	public AttendanceTargetService(
			DepartmentAuthorization authorization,
			DepartmentLock departmentLock,
			ActiveMembershipLookup membershipLookup,
			AttendanceDayMapper dayMapper,
			AttendanceRecordMapper recordMapper,
			AuditLogWriter auditLogWriter,
			Clock clock,
			ZoneId attendanceZone
	) {
		this.authorization = authorization;
		this.departmentLock = departmentLock;
		this.membershipLookup = membershipLookup;
		this.dayMapper = dayMapper;
		this.recordMapper = recordMapper;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
		this.attendanceZone = attendanceZone;
	}

	/**
	 * 현재 활성 소속 교사를 시작 전 날짜의 공식 대상자로 추가한다.
	 */
	@Transactional
	public void addTarget(
			AccountActor actor,
			long departmentId,
			long attendanceDayId,
			long memberId,
			String reason
	) {
		reason = requireReason(reason);
		AttendanceDayRow day = authorizeAndLockDay(actor, departmentId, attendanceDayId);
		ActiveMembership membership = membershipLookup.lockActive(departmentId, memberId);
		if (membership == null) {
			throw new ResourceNotFoundException("active membership");
		}
		AttendanceTargetRow target = recordMapper.lockTarget(
				departmentId,
				attendanceDayId,
				memberId);
		int changed;
		if (target == null) {
			changed = recordMapper.insertManualTarget(
					attendanceDayId,
					departmentId,
					memberId,
					membership.membershipId(),
					actor.accountId(),
					clock.instant(),
					reason);
		} else if (!target.isTarget()) {
			changed = recordMapper.reactivateManualTarget(
					attendanceDayId,
					departmentId,
					memberId,
					membership.membershipId(),
					actor.accountId(),
					clock.instant(),
					reason);
		} else {
			throw new BusinessRuleException("member is already an attendance target");
		}
		if (changed != 1) {
			throw new BusinessRuleException("attendance target changed concurrently");
		}
		writeAudit(actor, departmentId, day.id(), memberId, false, true, reason);
	}

	/**
	 * 기록이 없는 시작 전 대상자를 행 삭제 없이 비활성화한다.
	 */
	@Transactional
	public void removeTarget(
			AccountActor actor,
			long departmentId,
			long attendanceDayId,
			long memberId,
			String reason
	) {
		reason = requireReason(reason);
		AttendanceDayRow day = authorizeAndLockDay(actor, departmentId, attendanceDayId);
		AttendanceTargetRow target = recordMapper.lockTarget(
				departmentId,
				attendanceDayId,
				memberId);
		if (target == null || !target.isTarget()) {
			throw new ResourceNotFoundException("active attendance target");
		}
		if (dayMapper.countMemberRecord(attendanceDayId, memberId) != 0
				|| dayMapper.excludeTarget(
						departmentId,
						attendanceDayId,
						memberId,
						actor.accountId(),
						clock.instant(),
						reason) != 1) {
			throw new BusinessRuleException("attendance target cannot be removed");
		}
		writeAudit(actor, departmentId, day.id(), memberId, true, false, reason);
	}

	/**
	 * 부서와 날짜를 순서대로 잠그고 일반 변경 마감 전인지 확인한다.
	 */
	private AttendanceDayRow authorizeAndLockDay(
			AccountActor actor,
			long departmentId,
			long attendanceDayId
	) {
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		AttendanceDayRow day = dayMapper.lockDay(departmentId, attendanceDayId);
		if (day == null) {
			throw new ResourceNotFoundException("attendance day");
		}
		Instant start = ZonedDateTime.of(
				day.attendanceDate(),
				day.checkInStartTime(),
				attendanceZone).toInstant();
		if (!"SCHEDULED".equals(day.status()) || !clock.instant().isBefore(start)) {
			throw new BusinessRuleException("attendance target can no longer be changed");
		}
		return day;
	}

	/**
	 * 대상자 행을 지우지 않고 바뀐 boolean 상태와 사유를 감사한다.
	 */
	private void writeAudit(
			AccountActor actor,
			long departmentId,
			long attendanceDayId,
			long memberId,
			boolean before,
			boolean after,
			String reason
	) {
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				attendanceDayId,
				"ATTENDANCE_TARGET_CHANGED",
				"ATTENDANCE_TARGET",
				attendanceDayId + ":" + memberId,
				Map.of("isTarget", before),
				Map.of("isTarget", after),
				reason);
	}

	/**
	 * 일반 대상자 변경 사유를 공통 길이로 정규화한다.
	 */
	private static String requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		reason = reason.trim();
		if (reason.length() > 500) {
			throw new IllegalArgumentException("reason must not exceed 500 characters");
		}
		return reason;
	}
}
