package com.example.attend.access.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.DepartmentAuthorization;
import com.example.attend.access.api.SystemAuthorization;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationMapper;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationRow;
import com.example.attend.access.infrastructure.mybatis.DepartmentAdminInvitationOutboxMapper;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 부서 범위 관리자 초대와 내구성 있는 이메일 전달 작업을 만든다. */
@Service
public class DepartmentAdminInvitationService {
    private final SystemAuthorization systemAuthorization;
    private final DepartmentAuthorization departmentAuthorization;
    private final AdminWriteGate writeGate;
    private final AccountAdministrationMapper mapper;
    private final DepartmentAdminInvitationOutboxMapper outboxMapper;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    /** 부서·시스템 권한, 계정 저장, audit 저장 경계를 주입한다. */
    public DepartmentAdminInvitationService(
            SystemAuthorization systemAuthorization,
            DepartmentAuthorization departmentAuthorization,
            AdminWriteGate writeGate,
            AccountAdministrationMapper mapper,
            DepartmentAdminInvitationOutboxMapper outboxMapper,
            AuditLogWriter auditLogWriter,
            Clock clock) {
        this.systemAuthorization = systemAuthorization;
        this.departmentAuthorization = departmentAuthorization;
        this.writeGate = writeGate;
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    /** 시스템 관리자는 모든 부서, 부서 관리자는 자신의 부서만 초대할 수 있다. */
    @Transactional
    public long invite(AccountActor actor, long departmentId, String email, boolean systemRoute) {
        writeGate.requireEnabled();
        if (systemRoute) {
            systemAuthorization.requireSystemAdmin(actor);
        } else {
            departmentAuthorization.requireDepartmentAdmin(actor, departmentId);
        }
        Map<String, Object> department = mapper.selectDepartment(departmentId);
        if (department == null) {
            throw new ResourceNotFoundException("department");
        }
        if (!Boolean.TRUE.equals(department.get("active"))) {
            throw new BusinessRuleException("inactive department cannot send invitations");
        }
        String normalizedEmail = normalizeEmail(email);
        AccountAdministrationRow account = mapper.selectAccountByUsername(normalizedEmail);
        boolean newlyCreated = false;
        if (account == null) {
            try {
                long accountId = mapper.insertPendingAccount(normalizedEmail, null);
                account = mapper.selectAccount(accountId);
                newlyCreated = true;
            } catch (DuplicateKeyException exception) {
                account = mapper.selectAccountByUsername(normalizedEmail);
            }
        }
        if (account == null) {
            throw new BusinessRuleException("account invitation could not be created");
        }
        if ("DISABLED".equals(account.status())) {
            throw new BusinessRuleException("disabled account cannot be invited");
        }
		Long roleId = mapper.insertDepartmentRole(
				account.id(), departmentId, actor.accountId(), clock.instant());
		if (roleId != null) {
            auditLogWriter.writeAccount(departmentId, actor, null, "DEPARTMENT_ROLE_ASSIGNED",
                    "ACCOUNT", Long.toString(account.id()), null,
                    Map.of("role", "DEPARTMENT_ADMIN", "invited", true), null);
        }
        String deliveryType = "ACTIVE".equals(account.status())
                ? "ROLE_ASSIGNED" : "INVITATION";
        long outboxId = mapper.insertDepartmentAdminInvitationOutbox(
                account.id(), departmentId, actor.accountId(), deliveryType, normalizedEmail);
        auditLogWriter.writeAccount(departmentId, actor, null,
                newlyCreated ? "DEPARTMENT_ADMIN_INVITED" : "DEPARTMENT_ADMIN_INVITATION_QUEUED",
                "ACCOUNT", Long.toString(account.id()), null,
                Map.of("deliveryType", deliveryType, "outboxId", outboxId), null);
        return outboxId;
    }

    /** 실패한 메일만 권한 범위 안에서 다시 전송 대기 상태로 돌린다. */
    @Transactional
    public void resend(
            AccountActor actor, long departmentId, long outboxId, boolean systemRoute) {
        writeGate.requireEnabled();
        if (systemRoute) {
            systemAuthorization.requireSystemAdmin(actor);
        } else {
            departmentAuthorization.requireDepartmentAdmin(actor, departmentId);
        }
        if (outboxMapper.resetDead(outboxId, departmentId, clock.instant()) != 1) {
            throw new BusinessRuleException("failed invitation is not available for resend");
        }
        auditLogWriter.writeAccount(departmentId, actor, null,
                "DEPARTMENT_ADMIN_INVITATION_RESEND_QUEUED", "INVITATION",
                Long.toString(outboxId), null, Map.of("outboxId", outboxId), null);
    }

    /** 자기 부서의 최근 전달 상태를 보여주되 다른 부서 정보는 열람하지 않는다. */
    @Transactional(readOnly = true)
    public java.util.List<Map<String, Object>> invitations(
            AccountActor actor, long departmentId) {
        departmentAuthorization.requireDepartmentAdmin(actor, departmentId);
        return mapper.selectDepartmentAdminInvitationOutbox(departmentId);
    }

    private static String normalizeEmail(String value) {
        if (value == null) {
            throw new IllegalArgumentException("email is required");
        }
        String email = value.trim().toLowerCase(Locale.ROOT);
        int atIndex = email.indexOf('@');
        int finalDotIndex = email.lastIndexOf('.');
        if (email.length() > 100
                || atIndex <= 0
                || atIndex != email.lastIndexOf('@')
                || finalDotIndex <= atIndex + 1
                || finalDotIndex == email.length() - 1
                || email.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("email is invalid");
        }
        return email;
    }
}
