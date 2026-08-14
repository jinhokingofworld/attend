package com.example.attend.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.access.api.SystemAuthorization;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationMapper;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationRow;
import com.example.attend.access.infrastructure.mybatis.DepartmentAdminInvitationOutboxMapper;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 관리자 초대의 부서 경계·계정 상태·재전송 조건을 단위 검증한다. */
class DepartmentAdminInvitationServiceTest {

    private final SystemAuthorization systemAuthorization = mock(SystemAuthorization.class);
    private final DepartmentAuthorization departmentAuthorization = mock(DepartmentAuthorization.class);
    private final AdminWriteGate writeGate = mock(AdminWriteGate.class);
    private final AccountAdministrationMapper mapper = mock(AccountAdministrationMapper.class);
    private final DepartmentAdminInvitationOutboxMapper outboxMapper =
            mock(DepartmentAdminInvitationOutboxMapper.class);
    private final AuditLogWriter auditLogWriter = mock(AuditLogWriter.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    private final DepartmentAdminInvitationService service = new DepartmentAdminInvitationService(
            systemAuthorization, departmentAuthorization, writeGate, mapper, outboxMapper,
            auditLogWriter, clock);

    @Test
    void departmentAdminCreatesPendingEmailAccountRoleAndDeliveryJob() {
        AccountActor actor = new AccountActor(3L);
        when(mapper.selectDepartment(7L)).thenReturn(Map.of("active", true, "name", "Youth"));
        when(mapper.selectAccountByUsername("new.admin@example.com")).thenReturn(null);
        when(mapper.insertPendingAccount("new.admin@example.com", null)).thenReturn(11L);
        when(mapper.selectAccount(11L)).thenReturn(pending(11L, "new.admin@example.com"));
        when(mapper.countActiveRole(11L, 7L)).thenReturn(0);
        when(mapper.insertDepartmentAdminInvitationOutbox(
                eq(11L), eq(7L), eq(3L), eq("INVITATION"), eq("new.admin@example.com")))
                .thenReturn(31L);

        long outboxId = service.invite(actor, 7L, " New.Admin@Example.com ", false);

        assertThat(outboxId).isEqualTo(31L);
        verify(departmentAuthorization).requireDepartmentAdmin(actor, 7L);
        verify(mapper).insertDepartmentRole(eq(11L), eq(7L), eq(3L), any());
    }

    @Test
    void systemAdministratorMayInviteWithoutDepartmentRoleAndActiveAccountGetsNotice() {
        AccountActor actor = new AccountActor(1L);
        AccountAdministrationRow active = new AccountAdministrationRow(
                9L, "active@example.com", "hash", null, "ACTIVE", Instant.EPOCH, Instant.EPOCH);
        when(mapper.selectDepartment(7L)).thenReturn(Map.of("active", true));
        when(mapper.selectAccountByUsername("active@example.com")).thenReturn(active);
        when(mapper.countActiveRole(9L, 7L)).thenReturn(1);
        when(mapper.insertDepartmentAdminInvitationOutbox(
                9L, 7L, 1L, "ROLE_ASSIGNED", "active@example.com")).thenReturn(32L);

        assertThat(service.invite(actor, 7L, "active@example.com", true)).isEqualTo(32L);

        verify(systemAuthorization).requireSystemAdmin(actor);
        verify(departmentAuthorization, never()).requireDepartmentAdmin(any(), anyLong());
    }

    @Test
    void disabledDepartmentCannotQueueOrResendInvitation() {
        when(mapper.selectDepartment(7L)).thenReturn(Map.of("active", false));

        assertThatThrownBy(() -> service.invite(new AccountActor(2L), 7L, "x@example.com", false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inactive department");
    }

    @Test
    void departmentAdminCanOnlyResetAnEligibleFailedJob() {
        AccountActor actor = new AccountActor(2L);
        when(outboxMapper.resetDead(eq(44L), eq(7L), any())).thenReturn(1);

        service.resend(actor, 7L, 44L, false);

        verify(departmentAuthorization).requireDepartmentAdmin(actor, 7L);
        verify(outboxMapper).resetDead(eq(44L), eq(7L), any());
    }

    private static AccountAdministrationRow pending(long id, String email) {
        return new AccountAdministrationRow(id, email, null, null,
                "PENDING_SETUP", null, Instant.EPOCH);
    }
}
