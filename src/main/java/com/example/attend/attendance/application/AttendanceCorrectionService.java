package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.domain.AttendanceBand;
import com.example.attend.attendance.domain.AttendanceDecision;
import com.example.attend.attendance.domain.AttendancePolicy;
import com.example.attend.attendance.domain.AttendancePolicyEvaluator;
import com.example.attend.attendance.domain.AttendanceStatus;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayRow;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceRecordMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceRecordRow;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceTargetRow;
import com.example.attend.attendance.infrastructure.mybatis.MembershipPeriodRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.DepartmentLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수동 출석 등록·정정과 누락 대상자 추가를 고정 정책으로 처리한다.
 */
@Service
public class AttendanceCorrectionService {

	private final DepartmentAuthorization authorization;
	private final AdminWriteAuthorization writeAuthorization;
	private final DepartmentLock departmentLock;
	private final AttendanceDayMapper dayMapper;
	private final AttendancePolicyMapper policyMapper;
	private final AttendanceRecordMapper recordMapper;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;
	private final ZoneId attendanceZone;
	private final AttendancePolicyEvaluator evaluator = new AttendancePolicyEvaluator();

	/**
	 * 수동 정정 유스케이스의 협력 객체를 주입받는다.
	 */
	public AttendanceCorrectionService(
			AdminWriteAuthorization writeAuthorization,
			DepartmentAuthorization authorization,
			DepartmentLock departmentLock,
			AttendanceDayMapper dayMapper,
			AttendancePolicyMapper policyMapper,
			AttendanceRecordMapper recordMapper,
			AuditLogWriter auditLogWriter,
			Clock clock,
			ZoneId attendanceZone
	) {
		this.writeAuthorization = writeAuthorization;
		this.authorization = authorization;
		this.departmentLock = departmentLock;
		this.dayMapper = dayMapper;
		this.policyMapper = policyMapper;
		this.recordMapper = recordMapper;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
		this.attendanceZone = attendanceZone;
	}

	/**
	 * 클라이언트 상태값 대신 실제 시각과 고정 정책으로 결과를 계산해 저장한다.
	 */
	@Transactional
	public ManualAttendanceResult correct(
			AccountActor actor,
			long departmentId,
			long attendanceDayId,
			long memberId,
			ManualAttendanceCommand command
	) {
		writeAuthorization.requireEnabled();
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		AttendanceDayRow day = dayMapper.lockDay(departmentId, attendanceDayId);
		if (day == null) {
			throw new ResourceNotFoundException("attendance day");
		}
		if ("CANCELED".equals(day.status())) {
			throw new BusinessRuleException("canceled attendance day cannot be corrected");
		}
		if (command.actualCheckInAt() != null
				&& command.actualCheckInAt().isAfter(clock.instant())) {
			throw new BusinessRuleException("actual check-in time cannot be in the future");
		}

		AttendanceTargetRow target = recordMapper.lockTarget(
				departmentId,
				attendanceDayId,
				memberId);
		target = ensureActiveTarget(
				target,
				actor,
				departmentId,
				day,
				memberId,
				command);
		requireMembershipPeriod(target, command.actualCheckInAt(), command.markAbsent());

		CalculatedAttendance calculated = calculate(day, command);
		AttendanceRecordRow previous = recordMapper.lockRecord(attendanceDayId, memberId);
		boolean created = previous == null;
		long recordId;
		if (created) {
			recordId = recordMapper.insertManualRecord(
					day.id(),
					day.policyVersionId(),
					memberId,
					calculated.bandId(),
					calculated.status().name(),
					calculated.bandSequence(),
					calculated.bandLabel(),
					calculated.checkedInAt(),
					command.note(),
					actor.accountId());
		} else {
			if (recordMapper.updateManualRecord(
					previous.id(),
					day.policyVersionId(),
					calculated.bandId(),
					calculated.status().name(),
					calculated.bandSequence(),
					calculated.bandLabel(),
					calculated.checkedInAt(),
					command.note(),
					actor.accountId()) != 1) {
				throw new BusinessRuleException("attendance record changed concurrently");
			}
			recordId = previous.id();
		}

		auditLogWriter.writeAccount(
				departmentId,
				actor,
				attendanceDayId,
				created ? "ATTENDANCE_MANUALLY_ADDED" : "ATTENDANCE_CORRECTED",
				"ATTENDANCE_RECORD",
				Long.toString(recordId),
				toAudit(previous),
				Map.of(
						"status", calculated.status().name(),
						"bandLabel", calculated.bandLabel() == null
								? ""
								: calculated.bandLabel(),
						"memberId", memberId),
				command.reason());
		return new ManualAttendanceResult(
				recordId,
				calculated.status(),
				calculated.bandLabel(),
				created);
	}

	/**
	 * 기존 상태·구간·source를 유지하고 비고와 감사 이력만 갱신한다.
	 */
	@Transactional
	public void updateNote(
			AccountActor actor,
			long departmentId,
			long attendanceDayId,
			long memberId,
			String note,
			String reason
	) {
		writeAuthorization.requireEnabled();
		note = normalizeNote(note);
		reason = normalizeReason(reason);
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		AttendanceDayRow day = dayMapper.lockDay(departmentId, attendanceDayId);
		if (day == null || "CANCELED".equals(day.status())) {
			throw new ResourceNotFoundException("attendance day");
		}
		AttendanceRecordRow record = recordMapper.lockRecord(attendanceDayId, memberId);
		if (record == null
				|| recordMapper.updateNoteOnly(
						record.id(),
						note,
						actor.accountId()) != 1) {
			throw new ResourceNotFoundException("attendance record");
		}
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				attendanceDayId,
				"ATTENDANCE_NOTE_UPDATED",
				"ATTENDANCE_RECORD",
				Long.toString(record.id()),
				Map.of("notePresent", record.note() != null, "source", record.source()),
				Map.of("notePresent", note != null, "source", record.source()),
				reason);
	}

	/**
	 * 기존 활성 대상자를 사용하거나 실제 소속 기간을 근거로 누락 대상을 원자 추가한다.
	 */
	private AttendanceTargetRow ensureActiveTarget(
			AttendanceTargetRow target,
			AccountActor actor,
			long departmentId,
			AttendanceDayRow day,
			long memberId,
			ManualAttendanceCommand command
	) {
		if (target != null && target.isTarget()) {
			return target;
		}
		if (!command.addMissingTarget() || command.actualCheckInAt() == null) {
			throw new BusinessRuleException("member is not an active attendance target");
		}
		MembershipPeriodRow membership = recordMapper.lockMembershipAt(
				departmentId,
				memberId,
				command.actualCheckInAt());
		if (membership == null) {
			throw new BusinessRuleException(
					"actual check-in time is outside department membership");
		}
		if (target == null) {
			if (recordMapper.insertManualTarget(
					day.id(),
					departmentId,
					memberId,
					membership.id(),
					actor.accountId(),
					clock.instant(),
					command.reason()) != 1) {
				throw new BusinessRuleException("attendance target could not be added");
			}
		} else if (recordMapper.reactivateManualTarget(
				day.id(),
				departmentId,
				memberId,
				membership.id(),
				actor.accountId(),
				clock.instant(),
				command.reason()) != 1) {
			throw new BusinessRuleException("attendance target changed concurrently");
		}
		return new AttendanceTargetRow(
				membership.id(),
				true,
				membership.joinedAt(),
				membership.endedAt());
	}

	/**
	 * 정상·지각의 실제 시각이 snapshot 소속 기간의 반개구간 안인지 확인한다.
	 */
	private void requireMembershipPeriod(
			AttendanceTargetRow target,
			Instant actualCheckInAt,
			boolean absent
	) {
		if (absent) {
			return;
		}
		if (actualCheckInAt.isBefore(target.joinedAt())
				|| (target.endedAt() != null && !actualCheckInAt.isBefore(target.endedAt()))) {
			throw new BusinessRuleException(
					"actual check-in time is outside department membership");
		}
	}

	/**
	 * 결석은 빈 구간으로 만들고, 도착 시각은 날짜의 고정 정책으로 서버 계산한다.
	 */
	private CalculatedAttendance calculate(
			AttendanceDayRow day,
			ManualAttendanceCommand command
	) {
		if (command.markAbsent()) {
			return new CalculatedAttendance(
					AttendanceStatus.ABSENT,
					null,
					null,
					null,
					null);
		}
		LocalDate actualDate = LocalDate.ofInstant(
				command.actualCheckInAt(),
				attendanceZone);
		if (!day.attendanceDate().equals(actualDate)) {
			throw new BusinessRuleException(
					"actual check-in time must be on the attendance date");
		}
		List<AttendanceBand> bands = policyMapper.selectBands(day.policyVersionId());
		AttendancePolicy policy = new AttendancePolicy(
				day.policyVersionId(),
				day.checkInStartTime(),
				bands);
		AttendanceDecision decision = evaluator.evaluate(
				policy,
				command.actualCheckInAt(),
				attendanceZone);
		if (!(decision instanceof AttendanceDecision.Matched matched)) {
			throw new BusinessRuleException(
					"actual check-in time is outside the policy window");
		}
		AttendanceBand band = matched.band();
		return new CalculatedAttendance(
				AttendanceStatus.valueOf(band.parentStatus().name()),
				band.id(),
				band.sequenceNo(),
				band.label(),
				command.actualCheckInAt());
	}

	/**
	 * 기존 기록에서 감사에 허용한 상태·단계·원천만 추출한다.
	 */
	private static Map<String, Object> toAudit(AttendanceRecordRow record) {
		if (record == null) {
			return null;
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("status", record.status());
		data.put("bandLabel", record.bandLabelSnapshot());
		data.put("source", record.source());
		return data;
	}

	/**
	 * 빈 비고를 NULL로 바꾸고 화면 계약의 길이를 강제한다.
	 */
	private static String normalizeNote(String note) {
		if (note == null || note.isBlank()) {
			return null;
		}
		note = note.trim();
		if (note.length() > 1000) {
			throw new IllegalArgumentException("note must not exceed 1000 characters");
		}
		return note;
	}

	/**
	 * 메모 변경도 감사 가능한 비어 있지 않은 사유를 요구한다.
	 */
	private static String normalizeReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		reason = reason.trim();
		if (reason.length() > 500) {
			throw new IllegalArgumentException("reason must not exceed 500 characters");
		}
		return reason;
	}

	/**
	 * 출석 기록 컬럼에 그대로 저장할 서버 계산 결과다.
	 */
	private record CalculatedAttendance(
			AttendanceStatus status,
			Long bandId,
			Integer bandSequence,
			String bandLabel,
			Instant checkedInAt
	) {
	}
}
