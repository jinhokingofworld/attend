package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayRow;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyMapper;
import com.example.attend.attendance.infrastructure.mybatis.PolicyVersionRow;
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
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 부서별 출석일과 대상 교사 snapshot의 생명주기를 관리한다.
 */
@Service
public class AttendanceDayService {

	private final DepartmentAuthorization authorization;
	private final AdminWriteAuthorization writeAuthorization;
	private final DepartmentLock departmentLock;
	private final AttendancePolicyMapper policyMapper;
	private final AttendanceDayMapper dayMapper;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;
	private final ZoneId attendanceZone;

	/**
	 * 출석일 유스케이스의 협력 객체를 주입받는다.
	 */
	public AttendanceDayService(
			AdminWriteAuthorization writeAuthorization,
			DepartmentAuthorization authorization,
			DepartmentLock departmentLock,
			AttendancePolicyMapper policyMapper,
			AttendanceDayMapper dayMapper,
			AuditLogWriter auditLogWriter,
			Clock clock,
			ZoneId attendanceZone
	) {
		this.writeAuthorization = writeAuthorization;
		this.authorization = authorization;
		this.departmentLock = departmentLock;
		this.policyMapper = policyMapper;
		this.dayMapper = dayMapper;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
		this.attendanceZone = attendanceZone;
	}

	/**
	 * 오늘 또는 미래 날짜를 만들고 현재 활성 소속을 대상자로 고정한다.
	 *
	 * @return 생성된 출석일 식별자
	 */
	@Transactional
	public long createDay(
			AccountActor actor,
			long departmentId,
			LocalDate attendanceDate,
			long policyVersionId
	) {
		writeAuthorization.requireEnabled();
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		PolicyVersionRow policy = requirePublishedPolicy(departmentId, policyVersionId);
		requireCreatableDate(attendanceDate, policy);
		Long dayId = createDayIfAbsent(
				actor, departmentId, attendanceDate, policyVersionId);
		if (dayId == null) {
			throw new BusinessRuleException(
					"이 부서에 같은 출석 날짜가 이미 있습니다.");
		}
		return dayId;
	}

	/**
	 * 달력 반복 규칙에 맞는 출석 날짜를 한 번에 만든다.
	 *
	 * <p>같은 부서·날짜가 이미 있으면 기존 날짜와 대상자 snapshot을 건드리지 않고
	 * 건너뛴다.</p>
	 */
	@Transactional
	public AttendanceDayBatchResult createDays(
			AccountActor actor,
			long departmentId,
			AttendanceDayScheduleCommand command
	) {
		writeAuthorization.requireEnabled();
		authorization.requireDepartmentAdmin(actor, departmentId);
		List<LocalDate> dates = command.occurrenceDates();
		if (dates.isEmpty()) {
			throw new BusinessRuleException(
					"선택한 반복 규칙으로 생성되는 출석 날짜가 없습니다.");
		}
		departmentLock.lockActive(departmentId);
		PolicyVersionRow policy = requirePublishedPolicy(
				departmentId, command.policyVersionId());
		for (LocalDate date : dates) {
			requireCreatableDate(date, policy);
		}

		int createdCount = 0;
		int skippedExistingCount = 0;
		for (LocalDate date : dates) {
			Long dayId = createDayIfAbsent(
					actor, departmentId, date, command.policyVersionId());
			if (dayId == null) {
				skippedExistingCount++;
			} else {
				createdCount++;
			}
		}
		return new AttendanceDayBatchResult(createdCount, skippedExistingCount);
	}

	/** 날짜와 활성 교사 대상자 snapshot을 생성하고, 생성 사실을 감사한다. */
	private Long createDayIfAbsent(
			AccountActor actor,
			long departmentId,
			LocalDate attendanceDate,
			long policyVersionId
	) {
		Long dayId = dayMapper.insertDayIfAbsent(
				departmentId,
				attendanceDate,
				policyVersionId,
				actor.accountId());
		if (dayId == null) {
			return null;
		}
		int targetCount = dayMapper.snapshotActiveMembers(dayId, departmentId);
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				dayId,
				"ATTENDANCE_DAY_CREATED",
				"ATTENDANCE_DAY",
				Long.toString(dayId),
				null,
				Map.of(
						"attendanceDate", attendanceDate.toString(),
						"policyVersionId", policyVersionId,
						"targetCount", targetCount),
				null);
		return dayId;
	}

	/**
	 * 태깅 시작 전이고 기록이 없는 날짜만 취소한다.
	 */
	@Transactional
	public void cancelDay(
			AccountActor actor,
			long departmentId,
			long attendanceDayId,
			String reason
	) {
		writeAuthorization.requireEnabled();
		reason = requireReason(reason);
		authorizeAndLock(actor, departmentId);
		AttendanceDayRow day = requireDay(departmentId, attendanceDayId);
		requireBeforeCheckIn(day, clock.instant());
		if (!"SCHEDULED".equals(day.status()) || dayMapper.countRecords(day.id()) != 0) {
			throw new BusinessRuleException("attendance day cannot be canceled");
		}
		requireSingleUpdate(dayMapper.cancelDay(
				departmentId,
				attendanceDayId,
				actor.accountId(),
				clock.instant(),
				reason));
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				attendanceDayId,
				"ATTENDANCE_DAY_CANCELED",
				"ATTENDANCE_DAY",
				Long.toString(attendanceDayId),
				Map.of("status", "SCHEDULED"),
				Map.of("status", "CANCELED"),
				reason);
	}

	/**
	 * 태깅 시작 전이고 기록이 없는 날짜의 고정 정책을 다른 발행 버전으로 바꾼다.
	 */
	@Transactional
	public void changePolicy(
			AccountActor actor,
			long departmentId,
			long attendanceDayId,
			long newPolicyVersionId
	) {
		writeAuthorization.requireEnabled();
		authorizeAndLock(actor, departmentId);
		AttendanceDayRow day = requireDay(departmentId, attendanceDayId);
		requireBeforeCheckIn(day, clock.instant());
		PolicyVersionRow newPolicy =
				requirePublishedPolicy(departmentId, newPolicyVersionId);
		Instant newPolicyStart = ZonedDateTime.of(
				day.attendanceDate(),
				newPolicy.checkInStartTime(),
				attendanceZone).toInstant();
		if (!clock.instant().isBefore(newPolicyStart)) {
			throw new BusinessRuleException(
					"the replacement policy has already started");
		}
		if (!"SCHEDULED".equals(day.status()) || dayMapper.countRecords(day.id()) != 0) {
			throw new BusinessRuleException("attendance day policy cannot be changed");
		}
		requireSingleUpdate(dayMapper.updateDayPolicy(
				departmentId,
				attendanceDayId,
				newPolicyVersionId));
		auditLogWriter.writeAccount(
				departmentId,
				actor,
				attendanceDayId,
				"ATTENDANCE_DAY_POLICY_CHANGED",
				"ATTENDANCE_DAY",
				Long.toString(attendanceDayId),
				Map.of("policyVersionId", day.policyVersionId()),
				Map.of("policyVersionId", newPolicyVersionId),
				null);
	}

	/**
	 * 부서 권한 확인 뒤 공통 잠금 순서의 첫 행을 획득한다.
	 */
	private void authorizeAndLock(AccountActor actor, long departmentId) {
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
	}

	/**
	 * 대상 부서가 소유한 발행 정책만 반환한다.
	 */
	private PolicyVersionRow requirePublishedPolicy(long departmentId, long policyVersionId) {
		PolicyVersionRow policy = policyMapper.selectPublished(departmentId, policyVersionId);
		if (policy == null) {
			throw new ResourceNotFoundException("published attendance policy");
		}
		return policy;
	}

	/**
	 * 대상 부서의 날짜를 잠그고 찾지 못하면 범위 안전 예외를 던진다.
	 */
	private AttendanceDayRow requireDay(long departmentId, long attendanceDayId) {
		AttendanceDayRow day = dayMapper.lockDay(departmentId, attendanceDayId);
		if (day == null) {
			throw new ResourceNotFoundException("attendance day");
		}
		return day;
	}

	/** 과거 날짜와 이미 시작된 오늘 날짜 생성을 막는다. */
	private void requireCreatableDate(
			LocalDate attendanceDate,
			PolicyVersionRow policy
	) {
		if (attendanceDate == null || attendanceDate.isBefore(LocalDate.now(clock))) {
			throw new BusinessRuleException("과거 출석 날짜는 생성할 수 없습니다.");
		}
		if (attendanceDate.equals(LocalDate.now(clock))) {
			Instant start = ZonedDateTime.of(
					attendanceDate,
					policy.checkInStartTime(),
					attendanceZone).toInstant();
			if (!clock.instant().isBefore(start)) {
				throw new BusinessRuleException(
						"오늘 출석은 태깅 시작 시각이 지난 뒤 생성할 수 없습니다.");
			}
		}
	}

	/**
	 * 날짜와 고정 정책 시작 시각을 Instant로 합쳐 변경 마감 경계를 검사한다.
	 */
	private void requireBeforeCheckIn(AttendanceDayRow day, Instant now) {
		Instant checkInStart = ZonedDateTime.of(
				day.attendanceDate(),
				day.checkInStartTime(),
				attendanceZone).toInstant();
		if (!now.isBefore(checkInStart)) {
			throw new BusinessRuleException("attendance day has already started");
		}
	}

	/**
	 * 날짜 취소 사유를 DB 제약과 같은 범위로 정규화한다.
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

	/**
	 * 잠근 상태를 조건으로 한 UPDATE가 정확히 한 행을 바꿨는지 확인한다.
	 */
	private static void requireSingleUpdate(int rows) {
		if (rows != 1) {
			throw new BusinessRuleException("attendance day state changed concurrently");
		}
	}
}
