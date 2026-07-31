package com.example.attend.access.application;

import com.example.attend.access.infrastructure.mybatis.AccountAdministrationMapper;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * 로그인 계정 본인의 비밀번호 변경만 처리한다.
 */
@Service
public class OwnAccountService {

	private final AdminWriteGate writeGate;
	private final AccountAdministrationMapper mapper;
	private final PasswordEncoder passwordEncoder;
	private final AuditLogWriter auditLogWriter;
	private final Clock clock;

	/**
	 * 본인 계정 변경에 필요한 협력 객체를 주입받는다.
	 */
	public OwnAccountService(
			AdminWriteGate writeGate,
			AccountAdministrationMapper mapper,
			PasswordEncoder passwordEncoder,
			AuditLogWriter auditLogWriter,
			Clock clock) {
		this.writeGate = writeGate;
		this.mapper = mapper;
		this.passwordEncoder = passwordEncoder;
		this.auditLogWriter = auditLogWriter;
		this.clock = clock;
	}

	/**
	 * 현재 비밀번호를 확인하고 새 hash와 변경 시각을 저장한다.
	 */
	@Transactional
	public void changePassword(
			long accountId,
			String currentPassword,
			String newPassword,
			String confirmation) {
		writeGate.requireEnabled();
		PasswordPolicy.validate(newPassword, confirmation);
		AccountAdministrationRow account = mapper.lockAccount(accountId);
		if (account == null
				|| !"ACTIVE".equals(account.status())
				|| account.passwordHash() == null
				|| currentPassword == null
				|| !passwordEncoder.matches(
						currentPassword, account.passwordHash())) {
			throw new BusinessRuleException("current password is incorrect");
		}
		if (passwordEncoder.matches(newPassword, account.passwordHash())) {
			throw new BusinessRuleException(
					"new password must differ from current password");
		}
		if (mapper.updateOwnPassword(
				accountId,
				passwordEncoder.encode(newPassword),
				clock.instant()) != 1) {
			throw new BusinessRuleException("password could not be changed");
		}
		auditLogWriter.writeAccount(
				null,
				new com.example.attend.access.api.AccountActor(accountId),
				null,
				"ACCOUNT_PASSWORD_CHANGED",
				"ACCOUNT",
				Long.toString(accountId),
				null,
				Map.of("passwordChanged", true),
				null);
	}
}
