package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
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
	private final AdminWriteAuthorization writeAuthorization;
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
			AdminWriteAuthorization writeAuthorization,
			DepartmentAuthorization authorization,
			DepartmentLock departmentLock,
			AttendanceDayMapper dayMapper,
			MembershipClosure membershipClosure,
			AuditLogWriter auditLogWriter,
			Clock clock,
			ZoneId attendanceZone
	) {
		this.writeAuthorization = writeAuthorization;
		this.authorization = authorization;
		this.departmentLock = departmentLock;
		this.dayMapper = dayMapper;
		this.membershipClosure = membershipClosure;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
		this.attendanceZone = attendanceZone;
	}

	/**
	 * 확인한 수의 시작 전 날짜를 자동 제외하고 조직 상태를 함께 종료한다.
	 */
	@Transactional
	public void exclude(
			AccountActor actor,
			long departmentId,
			long memberId,
			ExcludeTeacherCommand command
	) {
		writeAuthorization.requireEnabled();
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		Instant occurredAt = clock.instant();
		ZonedDateTime businessNow = ZonedDateTime.ofInstant(
				occurredAt, attendanceZone);
		List<AttendanceDayRow> days = dayMapper.lockFutureTargetDays(
				departmentId,
				memberId,
				businessNow.toLocalDate(),
				businessNow.toLocalTime());
		if (days.size() != command.expectedFutureAttendanceDayCount()) {
			throw new BusinessRuleException(
					"미래 출석일 수가 변경되었습니다. 현재 영향을 다시 확인해 주세요.");
		}
		for (AttendanceDayRow day : days) {
			requireTargetCanBeExcluded(day, occurredAt);
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
						"excludedFutureDayCount", days.size(),
						"excludedFutureDayIds", days.stream()
								.map(AttendanceDayRow::id)
								.toList(),
						"cardDisposition", command.cardDisposition().name()),
				command.reason());
	}

	/**
	 * 잠근 날짜가 아직 시작 전이고 출석 기록이 하나도 없는지 다시 확인한다.
	 */
	private void requireTargetCanBeExcluded(
			AttendanceDayRow day,
			Instant now
	) {
		Instant start = ZonedDateTime.of(
				day.attendanceDate(),
				day.checkInStartTime(),
				attendanceZone).toInstant();
		if (!"SCHEDULED".equals(day.status())
				|| !now.isBefore(start)
				|| dayMapper.countRecords(day.id()) != 0) {
			throw new BusinessRuleException("attendance target can no longer be changed");
		}
	}
}
