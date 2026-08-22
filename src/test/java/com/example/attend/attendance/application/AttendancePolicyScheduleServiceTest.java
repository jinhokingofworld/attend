package com.example.attend.attendance.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.AdminWriteAuthorization;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.attendance.domain.AttendanceParentStatus;
import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyMapper;
import com.example.attend.attendance.infrastructure.mybatis.AttendancePolicyScheduleMapper;
import com.example.attend.attendance.infrastructure.mybatis.PolicyScheduleRow;
import com.example.attend.attendance.infrastructure.mybatis.PolicyVersionRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.organization.api.DepartmentLock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 진행 중인 오늘 출석일은 정책 일정 갱신의 충돌 대상이 아님을 검증한다. */
class AttendancePolicyScheduleServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 22);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-22T01:00:00Z"), SEOUL); // 10:00 KST

    @Test
    void reenableAfterTodaysCheckInStartedOnlyChecksAndRestoresFutureDates() {
        AttendancePolicyMapper policyMapper = mock(AttendancePolicyMapper.class);
        AttendancePolicyScheduleMapper scheduleMapper = mock(AttendancePolicyScheduleMapper.class);
        AttendanceDayMapper dayMapper = mock(AttendanceDayMapper.class);
        AttendanceDayService dayService = mock(AttendanceDayService.class);
        long departmentId = 10L;
        long scheduleId = 20L;
        PolicyScheduleRow schedule = schedule("OFF", 30L);
        PolicyVersionRow policy = policy(30L, LocalTime.of(9, 0));
        when(scheduleMapper.lockSchedule(departmentId, scheduleId)).thenReturn(schedule);
        when(policyMapper.selectPublished(departmentId, policy.id())).thenReturn(policy);
        when(scheduleMapper.updateStatus(departmentId, scheduleId, "ON", 7L, null)).thenReturn(1);

        service(policyMapper, scheduleMapper, dayMapper, dayService)
                .setEnabled(new AccountActor(7L), departmentId, scheduleId, true);

        verify(dayMapper).selectFirstActiveDateConflict(departmentId, List.of(TOMORROW));
        verify(dayService).createDaysForPolicySchedule(
                any(AccountActor.class), eq(departmentId), eq(policy.id()), eq(scheduleId),
                eq(List.of(TOMORROW)));
    }

    @Test
    void replaceAfterTodaysCheckInStartedLeavesTodayOutOfCancellationAndConflictCheck() {
        AttendancePolicyMapper policyMapper = mock(AttendancePolicyMapper.class);
        AttendancePolicyScheduleMapper scheduleMapper = mock(AttendancePolicyScheduleMapper.class);
        AttendanceDayMapper dayMapper = mock(AttendanceDayMapper.class);
        AttendanceDayService dayService = mock(AttendanceDayService.class);
        long departmentId = 10L;
        long scheduleId = 20L;
        PolicyScheduleRow schedule = schedule("ON", 30L);
        PolicyVersionRow previousPolicy = policy(30L, LocalTime.of(9, 0));
        PolicyScheduleCommand command = command(LocalTime.of(8, 0));
        when(scheduleMapper.lockSchedule(departmentId, scheduleId)).thenReturn(schedule);
        when(policyMapper.selectPublished(departmentId, previousPolicy.id())).thenReturn(previousPolicy);
        when(policyMapper.selectNextVersionNo(departmentId)).thenReturn(2);
        when(policyMapper.insertDraft(eq(departmentId), eq(2), anyString(), any(LocalTime.class), eq(7L)))
                .thenReturn(31L);
        when(policyMapper.publish(departmentId, 31L, 7L, CLOCK.instant())).thenReturn(1);
        when(scheduleMapper.replaceSchedule(eq(departmentId), eq(scheduleId), eq(31L),
                any(LocalDate.class), any(LocalDate.class), anyString(), anyInt(), any(), any(), eq(7L)))
                .thenReturn(1);
        when(scheduleMapper.updateStatus(departmentId, scheduleId, "ON", 7L, null)).thenReturn(1);

        service(policyMapper, scheduleMapper, dayMapper, dayService)
                .replace(new AccountActor(7L), departmentId, scheduleId, command);

        verify(dayService).cancelFuturePolicyScheduleDays(
                any(AccountActor.class), eq(departmentId), eq(scheduleId), any(), eq(false));
        verify(dayMapper).selectFirstActiveDateConflict(departmentId, List.of(TOMORROW));
        verify(dayService).createDaysForPolicySchedule(
                any(AccountActor.class), eq(departmentId), eq(31L), eq(scheduleId),
                eq(List.of(TOMORROW)));
    }

    private static AttendancePolicyScheduleService service(
            AttendancePolicyMapper policyMapper,
            AttendancePolicyScheduleMapper scheduleMapper,
            AttendanceDayMapper dayMapper,
            AttendanceDayService dayService) {
        return new AttendancePolicyScheduleService(
                mock(AdminWriteAuthorization.class), mock(DepartmentAuthorization.class),
                mock(DepartmentLock.class), policyMapper, scheduleMapper, dayMapper, dayService,
                mock(AuditLogWriter.class), CLOCK);
    }

    private static PolicyScheduleRow schedule(String status, long policyVersionId) {
        return new PolicyScheduleRow(20L, 10L, policyVersionId, status, TODAY, TOMORROW,
                AttendanceDayRecurrence.DAILY, 1, null, null);
    }

    private static PolicyVersionRow policy(long id, LocalTime checkInStartTime) {
        return new PolicyVersionRow(id, 10L, "정책", checkInStartTime, "PUBLISHED");
    }

    private static PolicyScheduleCommand command(LocalTime checkInStartTime) {
        return new PolicyScheduleCommand(
                new PolicyDraftCommand("수정 정책", checkInStartTime, List.of(
                        new PolicyBandInput(1, "정상", AttendanceParentStatus.PRESENT,
                                LocalTime.of(9, 0)),
                        new PolicyBandInput(2, "지각", AttendanceParentStatus.LATE,
                                LocalTime.of(23, 59)))),
                TODAY, TOMORROW, AttendanceDayRecurrence.DAILY, 1, Set.of(), Set.of(),
                null, null, true);
    }
}
