package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.organization.api.DepartmentLock;
import com.example.attend.organization.api.MembershipClosure;
import com.example.attend.organization.api.MembershipClosureResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 미래 대상자 변경과 조직 소속·카드 종료를 하나의 트랜잭션으로 처리한다.
 */
@Service
public class DepartmentMembershipExclusionService {

	private final DepartmentAuthorization authorization;
	private final DepartmentLock departmentLock;
	private final AttendanceDayMapper dayMapper;
	private final MembershipClosure membershipClosure;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;
	private final ZoneId attendanceZone;

	/**
	 * 부서 제외 orchestration의 협력 객체를 주입받는다.
	 */
	public DepartmentMembershipExclusionService(
			DepartmentAuthorization authorization,
			DepartmentLock departmentLock,
			AttendanceDayMapper dayMapper,
			MembershipClosure membershipClosure,
			AuditLogWriter auditLogWriter,
			Clock clock,
			ZoneId attendanceZone
	) {
		this.authorization = authorization;
		this.departmentLock = departmentLock;
		this.dayMapper = dayMapper;
		this.membershipClosure = membershipClosure;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
		this.attendanceZone = attendanceZone;
	}

	/**
	 * 선택한 날짜를 ID 오름차순으로 잠근 뒤 대상자와 조직 상태를 함께 종료한다.
	 */
	@Transactional
	public void exclude(
			AccountActor actor,
			long departmentId,
			long memberId,
			ExcludeTeacherCommand command
	) {
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		Instant occurredAt = clock.instant();
		List<AttendanceDayRow> days = command.futureAttendanceDayIds().isEmpty()
				? List.of()
				: dayMapper.lockTargetDays(
						departmentId,
						memberId,
						command.futureAttendanceDayIds());
		if (days.size() != command.futureAttendanceDayIds().size()) {
			throw new BusinessRuleException("one or more target days cannot be changed");
		}
		for (AttendanceDayRow day : days) {
			requireTargetCanBeExcluded(day, memberId, occurredAt);
		}

		MembershipClosureResult closure = membershipClosure.close(
				departmentId,
				memberId,
				actor,
				command.cardDisposition(),
				command.reason(),
				occurredAt);
		for (AttendanceDayRow day : days) {
			if (dayMapper.excludeTarget(
					departmentId,
					day.id(),
					memberId,
					actor.accountId(),
					occurredAt,
					command.reason()) != 1) {
				throw new BusinessRuleException("attendance target changed concurrently");
			}
		}
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				null,
				"DEPARTMENT_MEMBERSHIP_ENDED",
				"MEMBER",
				Long.toString(memberId),
				Map.of("active", true),
				Map.of(
						"active", false,
						"membershipId", closure.membershipId(),
						"excludedFutureDayIds", command.futureAttendanceDayIds(),
						"cardDisposition", command.cardDisposition().name()),
				command.reason());
	}

	/**
	 * 잠근 날짜가 아직 시작 전이고 해당 교사 기록이 없는지 다시 확인한다.
	 */
	private void requireTargetCanBeExcluded(
			AttendanceDayRow day,
			long memberId,
			Instant now
	) {
		Instant start = ZonedDateTime.of(
				day.attendanceDate(),
				day.checkInStartTime(),
				attendanceZone).toInstant();
		if (!"SCHEDULED".equals(day.status())
				|| !now.isBefore(start)
				|| dayMapper.countMemberRecord(day.id(), memberId) != 0) {
			throw new BusinessRuleException("attendance target can no longer be changed");
		}
	}
}
