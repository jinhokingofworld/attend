package com.example.attend.attendance.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.domain.AttendanceBand;
import com.example.attend.attendance.domain.AttendancePolicy;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyScheduleMapper;
import com.example.attend.attendance.infrastructure.mybatis.PolicyScheduleRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.api.DepartmentLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 알람처럼 독립적으로 ON/OFF 되는 출석 정책 일정의 업무 흐름이다. */
@Service
public class AttendancePolicyScheduleService {
    private static final String ON = "ON";
    private static final String OFF = "OFF";
    private static final String ARCHIVED = "ARCHIVED";

    private final AdminWriteAuthorization writeAuthorization;
    private final DepartmentAuthorization authorization;
    private final DepartmentLock departmentLock;
    private final AttendancePolicyMapper policyMapper;
    private final AttendancePolicyScheduleMapper scheduleMapper;
    private final AttendanceDayMapper dayMapper;
    private final AttendanceDayService dayService;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public AttendancePolicyScheduleService(
            AdminWriteAuthorization writeAuthorization,
            DepartmentAuthorization authorization,
            DepartmentLock departmentLock,
            AttendancePolicyMapper policyMapper,
            AttendancePolicyScheduleMapper scheduleMapper,
            AttendanceDayMapper dayMapper,
            AttendanceDayService dayService,
            AuditLogWriter auditLogWriter,
            Clock clock) {
        this.writeAuthorization = writeAuthorization;
        this.authorization = authorization;
        this.departmentLock = departmentLock;
        this.policyMapper = policyMapper;
        this.scheduleMapper = scheduleMapper;
        this.dayMapper = dayMapper;
        this.dayService = dayService;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    /** 정책 템플릿과 반복 일정을 한 트랜잭션에서 만들고, ON이면 6개월치를 생성한다. */
    @Transactional
    public long create(AccountActor actor, long departmentId, PolicyScheduleCommand command) {
        authorizeAndLock(actor, departmentId);
        requireStartNotPast(command.startDate());
        validatePolicy(command);
        List<LocalDate> dates = futureDates(command);
        if (command.enabled()) {
            rejectConflict(departmentId, dates);
        }

        int versionNo = policyMapper.selectNextVersionNo(departmentId);
        long policyVersionId = policyMapper.insertDraft(
                departmentId, versionNo, command.policy().name(),
                command.policy().checkInStartTime(), actor.accountId());
        for (PolicyBandInput band : command.policy().bands()) {
            policyMapper.insertBand(policyVersionId, band);
        }
        requireSingleUpdate(policyMapper.publish(
                departmentId, policyVersionId, actor.accountId(), clock.instant()));
        long scheduleId = scheduleMapper.insertSchedule(
                departmentId, policyVersionId, command.enabled() ? ON : OFF,
                command.startDate(), command.endDate(), command.recurrence().name(),
                command.interval(), command.yearlyMonth(), command.yearlyDay(), actor.accountId());
        if (!command.weeklyDays().isEmpty()) {
            scheduleMapper.insertWeekdays(scheduleId, command.weeklyDays().stream().sorted().toList());
        }
        if (!command.monthlyDays().isEmpty()) {
            scheduleMapper.insertMonthdays(scheduleId, command.monthlyDays().stream().sorted().toList());
        }
        if (command.enabled() && !dates.isEmpty()) {
            dayService.createDaysForPolicySchedule(
                    actor, departmentId, policyVersionId, scheduleId, dates);
        }
        auditLogWriter.writeAccount(
                departmentId, actor, null, "ATTENDANCE_POLICY_SCHEDULE_CREATED",
                "ATTENDANCE_POLICY_SCHEDULE", Long.toString(scheduleId), null,
                Map.of("policyVersionId", policyVersionId, "enabled", command.enabled(),
                        "generatedDayCount", dates.size()), null);
        return scheduleId;
    }

    /** ON/OFF 전환은 미래 6개월 범위를 다시 충돌 검증하고 실제 날짜를 동기화한다. */
    @Transactional
    public void setEnabled(AccountActor actor, long departmentId, long scheduleId, boolean enabled) {
        authorizeAndLock(actor, departmentId);
        PolicyScheduleRow schedule = requireSchedule(departmentId, scheduleId);
        if (ARCHIVED.equals(schedule.status())) {
            throw new BusinessRuleException("보관된 출석 정책은 다시 활성화할 수 없습니다.");
        }
        if ((enabled && ON.equals(schedule.status()))
                || (!enabled && OFF.equals(schedule.status()))) {
            return;
        }
        if (enabled) {
            PolicyScheduleCommand command = commandFrom(schedule);
            List<LocalDate> dates = futureDates(command);
            rejectConflict(departmentId, dates);
            requireSingleUpdate(scheduleMapper.updateStatus(
                    departmentId, scheduleId, ON, actor.accountId(), null));
            if (!dates.isEmpty()) {
                dayService.createDaysForPolicySchedule(
                        actor, departmentId, schedule.policyVersionId(), scheduleId, dates);
            }
        } else {
            requireSingleUpdate(scheduleMapper.updateStatus(
                    departmentId, scheduleId, OFF, actor.accountId(), null));
            dayService.cancelFuturePolicyScheduleDays(
                    actor, departmentId, scheduleId, "정책을 OFF로 전환했습니다.");
        }
        auditLogWriter.writeAccount(
                departmentId, actor, null, "ATTENDANCE_POLICY_SCHEDULE_TOGGLED",
                "ATTENDANCE_POLICY_SCHEDULE", Long.toString(scheduleId),
                Map.of("status", schedule.status()), Map.of("status", enabled ? ON : OFF), null);
    }

    /** 수정은 새 불변 정책 버전을 만든 뒤, 아직 시작하지 않은 일정만 다시 생성한다. */
    @Transactional
    public void replace(AccountActor actor, long departmentId, long scheduleId, PolicyScheduleCommand command) {
        authorizeAndLock(actor, departmentId);
        PolicyScheduleRow previous = requireSchedule(departmentId, scheduleId);
        if (ARCHIVED.equals(previous.status())) {
            throw new BusinessRuleException("보관된 출석 정책은 수정할 수 없습니다.");
        }
        requireStartNotPast(command.startDate());
        validatePolicy(command);
        dayService.cancelFuturePolicyScheduleDays(actor, departmentId, scheduleId, "정책 수정으로 미래 일정을 갱신했습니다.");
        List<LocalDate> dates = futureDates(command);
        if (command.enabled()) rejectConflict(departmentId, dates);

        int versionNo = policyMapper.selectNextVersionNo(departmentId);
        long policyVersionId = policyMapper.insertDraft(departmentId, versionNo,
                command.policy().name(), command.policy().checkInStartTime(), actor.accountId());
        for (PolicyBandInput band : command.policy().bands()) policyMapper.insertBand(policyVersionId, band);
        requireSingleUpdate(policyMapper.publish(departmentId, policyVersionId, actor.accountId(), clock.instant()));
        requireSingleUpdate(scheduleMapper.replaceSchedule(departmentId, scheduleId, policyVersionId,
                command.startDate(), command.endDate(), command.recurrence().name(), command.interval(),
                command.yearlyMonth(), command.yearlyDay(), actor.accountId()));
        scheduleMapper.deleteWeekdays(scheduleId);
        scheduleMapper.deleteMonthdays(scheduleId);
        if (!command.weeklyDays().isEmpty()) scheduleMapper.insertWeekdays(
                scheduleId, command.weeklyDays().stream().sorted().toList());
        if (!command.monthlyDays().isEmpty()) scheduleMapper.insertMonthdays(
                scheduleId, command.monthlyDays().stream().sorted().toList());
        requireSingleUpdate(scheduleMapper.updateStatus(departmentId, scheduleId,
                command.enabled() ? ON : OFF, actor.accountId(), null));
        if (command.enabled() && !dates.isEmpty()) {
            dayService.createDaysForPolicySchedule(actor, departmentId, policyVersionId, scheduleId, dates);
        }
        auditLogWriter.writeAccount(departmentId, actor, null, "ATTENDANCE_POLICY_SCHEDULE_REPLACED",
                "ATTENDANCE_POLICY_SCHEDULE", Long.toString(scheduleId),
                Map.of("policyVersionId", previous.policyVersionId()),
                Map.of("policyVersionId", policyVersionId, "enabled", command.enabled()), null);
    }

    /** 보관은 되돌릴 수 없고 미래·미시작 출석일만 취소한다. */
    @Transactional
    public void archive(AccountActor actor, long departmentId, long scheduleId, String reason) {
        authorizeAndLock(actor, departmentId);
        PolicyScheduleRow schedule = requireSchedule(departmentId, scheduleId);
        if (ARCHIVED.equals(schedule.status())) {
            throw new BusinessRuleException("이미 보관된 출석 정책입니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("보관 사유를 입력하세요.");
        }
        requireSingleUpdate(scheduleMapper.updateStatus(
                departmentId, scheduleId, ARCHIVED, actor.accountId(), clock.instant()));
        dayService.cancelFuturePolicyScheduleDays(actor, departmentId, scheduleId, reason.trim());
        auditLogWriter.writeAccount(
                departmentId, actor, null, "ATTENDANCE_POLICY_SCHEDULE_ARCHIVED",
                "ATTENDANCE_POLICY_SCHEDULE", Long.toString(scheduleId),
                Map.of("status", schedule.status()), Map.of("status", ARCHIVED), reason.trim());
    }

    private PolicyScheduleCommand commandFrom(PolicyScheduleRow schedule) {
        Set<DayOfWeek> weekdays = scheduleMapper.selectWeekdayValues(schedule.id()).stream()
                .map(DayOfWeek::of).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<Integer> monthdays = Set.copyOf(scheduleMapper.selectMonthdays(schedule.id()));
        return new PolicyScheduleCommand(
                new PolicyDraftCommand("stored", java.time.LocalTime.MIDNIGHT, List.of()),
                schedule.startDate(), schedule.endDate(), schedule.recurrence(),
                schedule.intervalValue(), weekdays, monthdays,
                schedule.yearlyMonth(), schedule.yearlyDay(), true);
    }

    private List<LocalDate> futureDates(PolicyScheduleCommand command) {
        LocalDate today = LocalDate.now(clock);
        LocalDate horizon = today.plusMonths(6);
        return command.occurrenceDatesUntil(horizon).stream()
                .filter(date -> !date.isBefore(today))
                .toList();
    }

    private void rejectConflict(long departmentId, List<LocalDate> dates) {
        if (dates.isEmpty()) return;
        LocalDate conflict = dayMapper.selectFirstActiveDateConflict(departmentId, dates);
        if (conflict != null) {
            throw new BusinessRuleException("출석 정책 적용 날짜가 겹칩니다: " + conflict);
        }
    }

    private void validatePolicy(PolicyScheduleCommand command) {
        List<AttendanceBand> bands = command.policy().bands().stream()
                .map(band -> new AttendanceBand(
                        band.sequenceNo(), band.sequenceNo(), band.label(),
                        band.parentStatus(), band.upperTime()))
                .toList();
        new AttendancePolicy(1L, command.policy().checkInStartTime(), bands);
    }

    private void authorizeAndLock(AccountActor actor, long departmentId) {
        writeAuthorization.requireEnabled();
        authorization.requireDepartmentAdmin(actor, departmentId);
        departmentLock.lockActive(departmentId);
    }

    private PolicyScheduleRow requireSchedule(long departmentId, long scheduleId) {
        PolicyScheduleRow schedule = scheduleMapper.lockSchedule(departmentId, scheduleId);
        if (schedule == null) throw new ResourceNotFoundException("attendance policy schedule");
        return schedule;
    }

    private void requireStartNotPast(LocalDate startDate) {
        if (startDate.isBefore(LocalDate.now(clock))) {
            throw new BusinessRuleException("과거 날짜부터 출석 정책을 적용할 수 없습니다.");
        }
    }

    private static void requireSingleUpdate(int rows) {
        if (rows != 1) throw new BusinessRuleException("출석 정책 상태가 동시에 변경되었습니다.");
    }
}
