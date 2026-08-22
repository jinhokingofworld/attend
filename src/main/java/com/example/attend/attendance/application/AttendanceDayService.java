package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayRow;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyMapper;
import com.example.attend.attendance.infrastructure.mybatis.PolicyVersionRow;
import com.example.attend.attendance.domain.AttendanceBand;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.DepartmentLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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
	private final ApplicationEventPublisher eventPublisher;
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
			ApplicationEventPublisher eventPublisher,
			Clock clock,
			ZoneId attendanceZone
	) {
		this.writeAuthorization = writeAuthorization;
		this.authorization = authorization;
		this.departmentLock = departmentLock;
		this.policyMapper = policyMapper;
		this.dayMapper = dayMapper;
		this.auditLogWriter = auditLogWriter;
		this.eventPublisher = eventPublisher;
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
				actor, departmentId, attendanceDate, policyVersionId, null);
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
		requireDateNotPast(command.startDate());
		List<LocalDate> dates = command.occurrenceDates();
		if (dates.isEmpty()) {
			throw new BusinessRuleException(
					"선택한 반복 규칙으로 생성되는 출석 날짜가 없습니다.");
		}
		departmentLock.lockActive(departmentId);
		PolicyVersionRow policy = requirePublishedPolicy(
				departmentId, command.policyVersionId());
		requireCreatableDate(command.startDate(), policy);
		for (LocalDate date : dates) {
			requireCreatableDate(date, policy);
		}

		int createdCount = 0;
		int skippedExistingCount = 0;
		for (LocalDate date : dates) {
			Long dayId = createDayIfAbsent(
					actor, departmentId, date, command.policyVersionId(), null);
			if (dayId == null) {
				skippedExistingCount++;
			} else {
				createdCount++;
			}
		}
		return new AttendanceDayBatchResult(createdCount, skippedExistingCount);
	}

	/** 활성 정책 일정이 소유한 미래 출석일을 생성한다. */
	@Transactional
	public AttendanceDayBatchResult createDaysForPolicySchedule(
			AccountActor actor,
			long departmentId,
			long policyVersionId,
			long policyScheduleId,
			List<LocalDate> dates
	) {
		writeAuthorization.requireEnabled();
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		PolicyVersionRow policy = requirePublishedPolicy(departmentId, policyVersionId);
		int createdCount = 0;
		for (LocalDate date : dates) {
			requireCreatableDate(date, policy);
			Long dayId = createDayIfAbsent(
					actor, departmentId, date, policyVersionId, policyScheduleId);
			if (dayId == null) {
				throw new BusinessRuleException("같은 날짜에 적용되는 출석 정책이 이미 있습니다.");
			}
			createdCount++;
		}
		return new AttendanceDayBatchResult(createdCount, 0);
	}

	/** 정책을 끄거나 보관할 때 미래·미시작 출석일만 취소한다. */
	@Transactional
	public int cancelFuturePolicyScheduleDays(
			AccountActor actor,
			long departmentId,
			long policyScheduleId,
			String reason
	) {
		writeAuthorization.requireEnabled();
		authorization.requireDepartmentAdmin(actor, departmentId);
		departmentLock.lockActive(departmentId);
		Clock localClock = clock.withZone(attendanceZone);
		return dayMapper.cancelFutureScheduleDays(
				departmentId, policyScheduleId, LocalDate.now(localClock), LocalTime.now(localClock), actor.accountId(),
				clock.instant(), reason);
	}

	/** 날짜와 활성 교사 대상자 snapshot을 생성하고, 생성 사실을 감사한다. */
	private Long createDayIfAbsent(
			AccountActor actor,
			long departmentId,
			LocalDate attendanceDate,
			long policyVersionId,
			Long policyScheduleId
	) {
		Instant finalizationDueAt = finalizationDueAt(
				attendanceDate, policyVersionId);
		Long dayId = dayMapper.insertDayIfAbsent(
				departmentId,
				attendanceDate,
				policyVersionId,
				policyScheduleId,
				finalizationDueAt,
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
		eventPublisher.publishEvent(
				new AttendanceFinalizationScheduleChanged(finalizationDueAt));
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
		Instant finalizationDueAt = finalizationDueAt(
				day.attendanceDate(), newPolicyVersionId);
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
				newPolicyVersionId,
				finalizationDueAt));
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
		eventPublisher.publishEvent(
				new AttendanceFinalizationScheduleChanged(finalizationDueAt));
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
		requireDateNotPast(attendanceDate);
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

	/** occurrence 포함 여부와 무관하게 입력 날짜가 과거인지 검증한다. */
	private void requireDateNotPast(LocalDate date) {
		if (date == null || date.isBefore(LocalDate.now(clock))) {
			throw new BusinessRuleException("과거 출석 날짜는 생성할 수 없습니다.");
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

	/** 발행 정책의 마지막 포함 구간 직후에 실행할 고정 마감 시각을 계산한다. */
	private Instant finalizationDueAt(LocalDate date, long policyVersionId) {
		AttendanceBand lastBand = policyMapper.selectBands(policyVersionId)
				.stream()
				.reduce((ignored, current) -> current)
				.orElseThrow(() -> new BusinessRuleException(
						"published attendance policy has no bands"));
		return ZonedDateTime.of(date, lastBand.upperTime(), attendanceZone)
				.plus(1, ChronoUnit.MICROS)
				.toInstant();
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
