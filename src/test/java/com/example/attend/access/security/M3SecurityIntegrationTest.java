package com.example.attend.access.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.attend.access.domain.AccountSystemRole;
import com.example.attend.access.application.CredentialTokenService;
import com.example.attend.access.application.IssuedCredentialLink;
import com.example.attend.access.application.SystemAdministrationService;
import com.example.attend.access.domain.CredentialTokenPurpose;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.attendance.application.AttendanceDayService;
import com.example.attend.attendance.application.AttendancePolicyService;
import com.example.attend.attendance.application.PolicyBandInput;
import com.example.attend.attendance.application.PolicyDraftCommand;
import com.example.attend.attendance.domain.AttendanceParentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * M3 인증의 계정 상태와 역할별 HTTP 경계를 실제 PostgreSQL로 검증한다.
 *
 * <p>사용자의 최소 테스트 요청에 맞춰 로그인 성공 조건, 역할 분리, 장치 API의
 * stateless 거부, 로그아웃 CSRF만 한 클래스에서 확인한다.</p>
 */
@SpringBootTest(properties = {
		"attendance.admin.write-enabled=true",
		"attendance.admin.account-token-pepper="
				+ "test-pepper-that-is-at-least-thirty-two-bytes",
		"attendance.admin.public-base-url=https://attend.example.test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class M3SecurityIntegrationTest {

	/** Spring Boot가 테스트 데이터소스로 연결할 PostgreSQL 서버다. */
	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:15-alpine");

	private static final String SYSTEM_USERNAME = "system-admin";
	private static final String DEPARTMENT_USERNAME = "department-admin";
	private static final String PASSWORD = "safe-password-1234";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private AccountUserDetailsService userDetailsService;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SystemAdministrationService administrationService;

	@Autowired
	private CredentialTokenService credentialTokenService;

	@Autowired
	private AttendancePolicyService attendancePolicyService;

	@Autowired
	private AttendanceDayService attendanceDayService;

	private long systemAccountId;
	private long departmentAccountId;
	private long departmentId;

	/**
	 * 각 테스트가 독립된 활성 계정과 부서 역할을 사용하도록 기준 데이터를 만든다.
	 */
	@BeforeEach
	void setUpAccounts() {
		jdbcTemplate.update("DELETE FROM public.audit_log");
		jdbcTemplate.update("DELETE FROM public.tag_event_log");
		jdbcTemplate.update("DELETE FROM public.attendance_record");
		jdbcTemplate.update("DELETE FROM public.attendance_target");
		jdbcTemplate.update("DELETE FROM public.attendance_day");
		jdbcTemplate.update("DELETE FROM public.attendance_band");
		jdbcTemplate.update("DELETE FROM public.attendance_policy_version");
		jdbcTemplate.update("DELETE FROM public.nfc_card_assignment");
		jdbcTemplate.update("DELETE FROM public.nfc_card");
		jdbcTemplate.update("DELETE FROM public.department_membership");
		jdbcTemplate.update("DELETE FROM public.device");
		jdbcTemplate.update(
				"DELETE FROM public.account_department_role");
		jdbcTemplate.update(
				"DELETE FROM public.account_credential_token");
		jdbcTemplate.update("DELETE FROM public.account");
		jdbcTemplate.update("DELETE FROM public.department");
		jdbcTemplate.update("DELETE FROM public.member");

		departmentId = insertAndReturnId(
				"INSERT INTO public.department(name) VALUES (?) RETURNING id",
				"아동부");
		systemAccountId = insertActiveAccount(
				SYSTEM_USERNAME,
				"SYSTEM_ADMIN");
		departmentAccountId = insertActiveAccount(
				DEPARTMENT_USERNAME,
				null);
		jdbcTemplate.update("""
				INSERT INTO public.account_department_role(
				    account_id,
				    department_id,
				    role,
				    assigned_by_account_id)
				VALUES (?, ?, 'DEPARTMENT_ADMIN', ?)
				""",
				departmentAccountId,
				departmentId,
				systemAccountId);
		jdbcTemplate.update("""
				INSERT INTO public.account(username, status)
				VALUES ('pending-admin', 'PENDING_SETUP')
				""");
	}

	/**
	 * 활성 계정만 조회되고 비밀번호 해시가 principal 로그나 세션에 남지 않는지 확인한다.
	 */
	@Test
	void authenticatesOnlyActiveAccountsWithoutLeakingCredentials() {
		AccountPrincipal principal = (AccountPrincipal)
				userDetailsService.loadUserByUsername("  SYSTEM-ADMIN ");

		assertThat(principal.accountId()).isEqualTo(systemAccountId);
		assertThat(principal.systemRole())
				.isEqualTo(AccountSystemRole.SYSTEM_ADMIN);
		assertThat(principal.getAuthorities())
				.extracting("authority")
				.containsExactly("ROLE_SYSTEM_ADMIN");
		assertThat(passwordEncoder.matches(PASSWORD, principal.getPassword()))
				.isTrue();
		assertThat(principal.toString())
				.doesNotContain(SYSTEM_USERNAME)
				.doesNotContain(principal.getPassword());

		principal.eraseCredentials();
		assertThat(principal.getPassword()).isNull();
		assertThatThrownBy(() ->
				userDetailsService.loadUserByUsername("pending-admin"))
				.isInstanceOf(UsernameNotFoundException.class)
				.hasMessage("Invalid username or password");
	}

	/**
	 * 두 관리자 역할과 장치 API가 각자의 보안 경계를 넘지 않는지 확인한다.
	 *
	 * @throws Exception MockMvc 요청 처리에 실패한 경우
	 */
	@Test
	void separatesWebRolesCsrfAndStatelessDeviceRequests() throws Exception {
		AccountPrincipal systemPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername(SYSTEM_USERNAME);
		AccountPrincipal departmentPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername(DEPARTMENT_USERNAME);

		mockMvc.perform(get("/admin"))
				.andExpect(status().is3xxRedirection());

		mockMvc.perform(post("/authentication")
						.with(csrf())
						.param("usernameInput", SYSTEM_USERNAME)
						.param("passwordInput", PASSWORD))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin"))
				.andExpect(authenticated().withUsername(SYSTEM_USERNAME));

		mockMvc.perform(get("/admin/system/departments")
						.with(user(systemPrincipal)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/admin/system/accounts")
						.with(user(systemPrincipal)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/admin/system/operations")
						.with(user(systemPrincipal)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/admin/system/audit")
						.with(user(systemPrincipal)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/admin/system/accounts/" + departmentAccountId)
						.with(user(systemPrincipal)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/admin/departments/1")
						.with(user(systemPrincipal)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/system/departments")
						.with(user(departmentPrincipal)))
				.andExpect(status().isForbidden());
		long otherDepartmentId = insertAndReturnId(
				"INSERT INTO public.department(name) VALUES (?) RETURNING id",
				"다른 부서");
		mockMvc.perform(get("/admin/departments/" + otherDepartmentId)
						.with(user(departmentPrincipal)))
				.andExpect(status().isNotFound());
		for (String path : new String[]{
				"/admin/departments/" + departmentId,
				"/admin/departments/" + departmentId + "/teachers",
				"/admin/departments/" + departmentId + "/cards/inbox",
				"/admin/departments/" + departmentId + "/policies",
				"/admin/departments/" + departmentId + "/attendance-days",
				"/admin/departments/" + departmentId + "/history"}) {
			mockMvc.perform(get(path).with(user(departmentPrincipal)))
					.andExpect(status().isOk());
		}

		var departmentActor =
				new com.example.attend.access.api.AccountActor(departmentAccountId);
		long policyId = attendancePolicyService.createDraft(
				departmentActor,
				departmentId,
				new PolicyDraftCommand(
						"화면 검증 정책",
						LocalTime.of(8, 30),
						List.of(
								new PolicyBandInput(
										1,
										"정상",
										AttendanceParentStatus.PRESENT,
										LocalTime.of(9, 0)),
								new PolicyBandInput(
										2,
										"지각",
										AttendanceParentStatus.LATE,
										LocalTime.of(9, 30)))));
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/policies/" + policyId)
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk());
		attendancePolicyService.publish(departmentActor, departmentId, policyId);
		long dayId = attendanceDayService.createDay(
				departmentActor,
				departmentId,
				LocalDate.now().plusDays(1),
				policyId);
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/attendance-days/" + dayId)
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/member").with(user(departmentPrincipal)))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/logout").with(user(systemPrincipal)))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/logout")
						.with(user(systemPrincipal))
						.with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?logout"));

		MvcResult deviceResult = mockMvc.perform(
						post("/api/v1/device/tag-events"))
				.andExpect(status().isServiceUnavailable())
				.andReturn();
		assertThat(deviceResult.getRequest().getSession(false)).isNull();
	}

	/**
	 * 초대·재설정 token이 30분·1회용이며 새 발급으로 이전 값이 폐기되는지 검증한다.
	 */
	@Test
	void completesInvitationAndResetTokenLifecycleWithoutStoringRawToken() {
		var actor = new com.example.attend.access.api.AccountActor(systemAccountId);
		long accountId = administrationService.createAccount(
				actor, "invited-admin", false);

		IssuedCredentialLink invitation = credentialTokenService.issue(
				actor, accountId, CredentialTokenPurpose.INVITATION);
		String invitationToken = rawToken(invitation);
		String storedHash = jdbcTemplate.queryForObject("""
				SELECT token_hash
				FROM public.account_credential_token
				WHERE account_id = ?
				  AND purpose = 'INVITATION'
				""", String.class, accountId);
		assertThat(storedHash)
				.hasSize(64)
				.doesNotContain(invitationToken);

		credentialTokenService.consume(
				CredentialTokenPurpose.INVITATION,
				invitationToken,
				"invited-password-1234",
				"invited-password-1234");
		assertThat(userDetailsService.loadUserByUsername("invited-admin"))
				.isNotNull();
		assertThatThrownBy(() -> credentialTokenService.consume(
				CredentialTokenPurpose.INVITATION,
				invitationToken,
				"another-password-1234",
				"another-password-1234"))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessage("credential link is invalid or expired");

		IssuedCredentialLink firstReset = credentialTokenService.issue(
				actor, accountId, CredentialTokenPurpose.RESET);
		IssuedCredentialLink replacementReset = credentialTokenService.issue(
				actor, accountId, CredentialTokenPurpose.RESET);
		assertThatThrownBy(() -> credentialTokenService.consume(
				CredentialTokenPurpose.RESET,
				rawToken(firstReset),
				"reset-password-123456",
				"reset-password-123456"))
				.isInstanceOf(BusinessRuleException.class);

		credentialTokenService.consume(
				CredentialTokenPurpose.RESET,
				rawToken(replacementReset),
				"reset-password-123456",
				"reset-password-123456");
		AccountPrincipal resetPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername("invited-admin");
		assertThat(passwordEncoder.matches(
				"reset-password-123456",
				resetPrincipal.getPassword())).isTrue();
	}

	private long insertActiveAccount(String username, String systemRole) {
		return insertAndReturnId("""
				INSERT INTO public.account(
				    username,
				    password_hash,
				    system_role,
				    status,
				    password_changed_at)
				VALUES (?, ?, ?, 'ACTIVE', ?)
				RETURNING id
				""",
				username,
				passwordEncoder.encode(PASSWORD),
				systemRole,
				OffsetDateTime.now());
	}

	private static String rawToken(IssuedCredentialLink issued) {
		return issued.link().substring(issued.link().indexOf("#token=") + 7);
	}

	private long insertAndReturnId(String sql, Object... arguments) {
		Long id = jdbcTemplate.queryForObject(sql, Long.class, arguments);
		if (id == null) {
			throw new IllegalStateException("Test fixture insert returned no id");
		}
		return id;
	}
}
