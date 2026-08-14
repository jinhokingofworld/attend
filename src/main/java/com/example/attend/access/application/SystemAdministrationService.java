package com.example.attend.access.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.SystemAuthorization;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationMapper;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 시스템 관리자의 부서·계정·부서 역할 명령과 조회를 제공한다.
 */
@Service
public class SystemAdministrationService {

	private final SystemAuthorization authorization;
	private final AdminWriteGate writeGate;
	private final AccountAdministrationMapper mapper;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;

	/**
	 * 시스템 관리 유스케이스의 협력 객체를 주입받는다.
	 */
	public SystemAdministrationService(
			SystemAuthorization authorization,
			AdminWriteGate writeGate,
			AccountAdministrationMapper mapper,
			AuditLogWriter auditLogWriter,
			Clock clock) {
		this.authorization = authorization;
		this.writeGate = writeGate;
		this.mapper = mapper;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
	}

	/** 활성 부서 목록과 관리자·장치 수를 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> departments(AccountActor actor) {
		authorization.requireSystemAdmin(actor);
		return mapper.selectDepartments();
	}

	/** 한 부서의 시스템 관리용 요약을 조회한다. */
	@Transactional(readOnly = true)
	public Map<String, Object> department(AccountActor actor, long departmentId) {
		authorization.requireSystemAdmin(actor);
		Map<String, Object> department = mapper.selectDepartment(departmentId);
		if (department == null) {
			throw new ResourceNotFoundException("department");
		}
		return department;
	}

	/** 부서 상세에 표시할 활성 관리자 계정을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> departmentAdministrators(
			AccountActor actor,
			long departmentId) {
		department(actor, departmentId);
		return mapper.selectDepartmentAdministrators(departmentId);
	}

	/** 부서 상세에 표시할 장치 상태 요약을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> departmentDevices(
			AccountActor actor,
			long departmentId) {
		department(actor, departmentId);
		return mapper.selectDepartmentDevices(departmentId);
	}

	/** 부서 상세에 최근 관리자 초대 메일 전달 상태를 표시한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> departmentInvitations(
			AccountActor actor, long departmentId) {
		department(actor, departmentId);
		return mapper.selectDepartmentAdminInvitationOutbox(departmentId);
	}

	/** 계정·권한을 암묵적으로 만들지 않고 부서 하나만 생성한다. */
	@Transactional
	public long createDepartment(AccountActor actor, String name) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		name = normalizeRequired(name, "department name", 100);
		try {
			long id = mapper.insertDepartment(name);
			auditLogWriter.writeAccount(
					id, actor, null, "DEPARTMENT_CREATED", "DEPARTMENT",
					Long.toString(id), null, Map.of("name", name), null);
			return id;
		} catch (DuplicateKeyException exception) {
			throw new BusinessRuleException("department name already exists");
		}
	}

	/** 시스템 관리자가 부서 이름만 변경한다. */
	@Transactional
	public void updateDepartment(AccountActor actor, long departmentId, String name) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		Map<String, Object> department = department(actor, departmentId);
		name = normalizeRequired(name, "department name", 100);
		try {
			if (mapper.updateDepartmentName(departmentId, name) != 1) {
				throw new ResourceNotFoundException("department");
			}
		} catch (DuplicateKeyException exception) {
			throw new BusinessRuleException("department name already exists");
		}
		auditLogWriter.writeAccount(departmentId, actor, null, "DEPARTMENT_RENAMED",
				"DEPARTMENT", Long.toString(departmentId),
				Map.of("name", department.get("name")), Map.of("name", name), null);
	}

	/** 물리 삭제 없이 부서를 비활성화해 업무와 장치 처리를 함께 막는다. */
	@Transactional
	public void deactivateDepartment(AccountActor actor, long departmentId) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		department(actor, departmentId);
		if (mapper.deactivateDepartment(departmentId) != 1) {
			throw new BusinessRuleException("department is already inactive");
		}
		auditLogWriter.writeAccount(departmentId, actor, null, "DEPARTMENT_DEACTIVATED",
				"DEPARTMENT", Long.toString(departmentId),
				Map.of("active", true), Map.of("active", false), null);
	}

	/** 보존된 역할·이력을 바꾸지 않고 부서를 재활성화한다. */
	@Transactional
	public void reactivateDepartment(AccountActor actor, long departmentId) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		department(actor, departmentId);
		if (mapper.reactivateDepartment(departmentId) != 1) {
			throw new BusinessRuleException("department is already active");
		}
		auditLogWriter.writeAccount(departmentId, actor, null, "DEPARTMENT_REACTIVATED",
				"DEPARTMENT", Long.toString(departmentId),
				Map.of("active", false), Map.of("active", true), null);
	}

	/** 관리자 계정 목록과 현재 역할 요약을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> accounts(AccountActor actor) {
		authorization.requireSystemAdmin(actor);
		return mapper.selectAccounts();
	}

	/** 한 계정의 상태와 시스템 역할을 조회한다. */
	@Transactional(readOnly = true)
	public AccountAdministrationRow account(AccountActor actor, long accountId) {
		authorization.requireSystemAdmin(actor);
		AccountAdministrationRow account = mapper.selectAccount(accountId);
		if (account == null) {
			throw new ResourceNotFoundException("account");
		}
		return account;
	}

	/** 한 계정의 현재 활성 부서 관리자 역할을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> roles(AccountActor actor, long accountId) {
		account(actor, accountId);
		return mapper.selectAccountDepartmentRoles(accountId);
	}

	/** 비밀번호 없는 {@code PENDING_SETUP} 계정을 생성한다. */
	@Transactional
	public long createAccount(
			AccountActor actor,
			String username,
			boolean systemAdmin) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		username = normalizeRequired(username, "username", 100);
		try {
			long id = mapper.insertPendingAccount(
					username,
					systemAdmin ? "SYSTEM_ADMIN" : null);
			auditLogWriter.writeAccount(
					null, actor, null, "ACCOUNT_CREATED", "ACCOUNT",
					Long.toString(id), null,
					Map.of(
							"username", username,
							"systemAdmin", systemAdmin,
							"status", "PENDING_SETUP"),
					null);
			return id;
		} catch (DuplicateKeyException exception) {
			throw new BusinessRuleException("username already exists");
		}
	}

	/** 자기 자신과 마지막 시스템 관리자를 제외한 계정을 비활성화한다. */
	@Transactional
	public void disableAccount(
			AccountActor actor,
			long accountId,
			String usernameConfirmation) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		AccountAdministrationRow account = requireLockedAccount(accountId);
		if (actor.accountId() == accountId) {
			throw new BusinessRuleException("current account cannot disable itself");
		}
		if (!account.username().equals(usernameConfirmation == null
				? null : usernameConfirmation.trim())) {
			throw new BusinessRuleException("username confirmation does not match");
		}
		if ("SYSTEM_ADMIN".equals(account.systemRole())
				&& "ACTIVE".equals(account.status())
				&& mapper.countOtherActiveSystemAdmins(accountId) == 0) {
			throw new BusinessRuleException(
					"last active system administrator cannot be disabled");
		}
		if (mapper.disableAccount(accountId) != 1) {
			throw new BusinessRuleException("account cannot be disabled");
		}
		mapper.revokeActiveTokens(accountId, "INVITATION", clock.instant());
		mapper.revokeActiveTokens(accountId, "RESET", clock.instant());
		auditLogWriter.writeAccount(
				null, actor, null, "ACCOUNT_DISABLED", "ACCOUNT",
				Long.toString(accountId),
				Map.of("status", account.status()),
				Map.of("status", "DISABLED"), "administrator command");
	}

	/** 비밀번호 존재 여부에 따라 이전 활성 상태로 계정을 되돌린다. */
	@Transactional
	public void enableAccount(AccountActor actor, long accountId) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		AccountAdministrationRow account = requireLockedAccount(accountId);
		if (mapper.enableAccount(accountId) != 1) {
			throw new BusinessRuleException("account cannot be enabled");
		}
		String nextStatus = account.passwordHash() == null
				? "PENDING_SETUP" : "ACTIVE";
		auditLogWriter.writeAccount(
				null, actor, null, "ACCOUNT_ENABLED", "ACCOUNT",
				Long.toString(accountId),
				Map.of("status", account.status()),
				Map.of("status", nextStatus), null);
	}

	/** 계정에 한 부서의 관리자 역할을 명시적으로 배정한다. */
	@Transactional
	public void assignDepartmentRole(
			AccountActor actor,
			long accountId,
			long departmentId) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		requireLockedAccount(accountId);
		if (mapper.selectDepartment(departmentId) == null) {
			throw new ResourceNotFoundException("department");
		}
		if (mapper.countActiveRole(accountId, departmentId) != 0) {
			throw new BusinessRuleException("department role is already active");
		}
		mapper.insertDepartmentRole(
				accountId, departmentId, actor.accountId(), clock.instant());
		auditLogWriter.writeAccount(
				departmentId, actor, null, "DEPARTMENT_ROLE_ASSIGNED",
				"ACCOUNT", Long.toString(accountId), null,
				Map.of("role", "DEPARTMENT_ADMIN"), null);
	}

	/** 계정의 활성 부서 관리자 역할을 이력 보존 방식으로 회수한다. */
	@Transactional
	public void revokeDepartmentRole(
			AccountActor actor,
			long accountId,
			long departmentId) {
		writeGate.requireEnabled();
		authorization.requireSystemAdmin(actor);
		if (mapper.revokeDepartmentRole(
				accountId, departmentId, clock.instant()) != 1) {
			throw new ResourceNotFoundException("active department role");
		}
		auditLogWriter.writeAccount(
				departmentId, actor, null, "DEPARTMENT_ROLE_REVOKED",
				"ACCOUNT", Long.toString(accountId),
				Map.of("role", "DEPARTMENT_ADMIN"), null, null);
	}

	/** 계정이 선택할 수 있는 활성 부서 작업 공간을 조회한다. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> workspaces(AccountActor actor) {
		return mapper.selectWorkspaces(actor.accountId());
	}

	/**
	 * 교사 개인정보를 포함하지 않는 시스템 운영 집계를 반환한다.
	 *
	 * @param actor 인증 시스템 관리자
	 * @return 운영 상태 집계
	 */
	@Transactional(readOnly = true, timeout = 3)
	public Map<String, Object> operations(AccountActor actor) {
		authorization.requireSystemAdmin(actor);
		return mapper.selectSystemOperations();
	}

	/**
	 * 시스템 lifecycle action allowlist에 해당하는 감사 이력을 반환한다.
	 *
	 * @param actor 인증 시스템 관리자
	 * @return 최근 시스템 감사 목록
	 */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> systemAudit(AccountActor actor) {
		authorization.requireSystemAdmin(actor);
		return mapper.selectSystemAudit();
	}

	private AccountAdministrationRow requireLockedAccount(long accountId) {
		AccountAdministrationRow account = mapper.lockAccount(accountId);
		if (account == null) {
			throw new ResourceNotFoundException("account");
		}
		return account;
	}

	private static String normalizeRequired(
			String value,
			String field,
			int maxCodePoints) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		value = value.trim();
		if (value.codePointCount(0, value.length()) > maxCodePoints) {
			throw new IllegalArgumentException(field + " is too long");
		}
		return value;
	}
}
