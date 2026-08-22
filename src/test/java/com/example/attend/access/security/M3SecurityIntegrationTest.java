package com.example.attend.access.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.application.CredentialTokenService;
import com.example.attend.access.application.IssuedCredentialLink;
import com.example.attend.access.application.SystemAdministrationService;
import com.example.attend.access.domain.AccountSystemRole;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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

	@Autowired
	private Clock clock;

	private long systemAccountId;
	private long departmentAccountId;
	private long departmentId;

	/** 빈 초안은 웹에서도 저장 가능하며 첫 동적 행은 정상 출석으로 시작한다. */
	@Test
	void preservesAnEmptyPolicyDraftAndProvidesAFirstPresentBandTemplate()
			throws Exception {
		AccountPrincipal departmentPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername(DEPARTMENT_USERNAME);

		mockMvc.perform(post("/admin/departments/" + departmentId + "/policies")
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("name", "빈 정책 초안")
						.param("checkInStartTime", "08:30"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(
						"/admin/departments/" + departmentId + "/policies"));

		long policyId = jdbcTemplate.queryForObject("""
				SELECT id
				FROM public.attendance_policy_version
				WHERE department_id = ? AND name = '빈 정책 초안'
				""", Long.class, departmentId);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.attendance_band
				WHERE policy_version_id = ?
				""", Integer.class, policyId)).isZero();

		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/policies/" + policyId)
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"id=\"edit-band-template\"")))
				.andExpect(content().string(containsString(
						"const isFirstBand = !bands.querySelector")))
				.andExpect(content().string(containsString(
						"? \"PRESENT\"")));

		mockMvc.perform(post("/admin/departments/" + departmentId
						+ "/policies/" + policyId + "/replace")
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("name", "빈 정책 초안 수정")
						.param("checkInStartTime", "08:40"))
				.andExpect(status().is3xxRedirection());
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.attendance_band
				WHERE policy_version_id = ?
				""", Integer.class, policyId)).isZero();
	}

	@Test
	void createsARecurringPolicyFromExplicitRepeatFieldsWithoutClientScript()
			throws Exception {
		AccountPrincipal departmentPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername(DEPARTMENT_USERNAME);
		LocalDate startDate = LocalDate.now(clock).plusDays(1);
		LocalDate endDate = startDate.plusDays(7);

		mockMvc.perform(post("/admin/departments/" + departmentId + "/policies")
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("name", "반복 화면 검증 정책")
						.param("checkInStartTime", "08:30")
						.param("bandLabel", "정상", "지각")
						.param("bandStatus", "PRESENT", "LATE")
						.param("bandUpperTime", "09:00", "09:30")
						.param("startDate", startDate.toString())
						.param("endDate", endDate.toString())
						.param("recurrence", "NONE")
						.param("repeatEnabled", "true")
						.param("recurrencePattern", "WEEKLY")
						.param("weeklyDays", "MONDAY"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(
						"/admin/departments/" + departmentId + "/policies"));

		assertThat(jdbcTemplate.queryForObject("""
				SELECT schedule.recurrence
				FROM public.attendance_policy_schedule AS schedule
				JOIN public.attendance_policy_version AS policy
				  ON policy.id = schedule.policy_version_id
				WHERE policy.department_id = ?
				  AND policy.name = '반복 화면 검증 정책'
				""", String.class, departmentId)).isEqualTo("WEEKLY");
	}

	/** 운영 집계는 오늘 날짜도 저장된 마감 시각이 지났으면 지연으로 표시한다. */
	@Test
	void countsDueSameDayAttendanceAsOverdueInSystemOperations()
			throws Exception {
		long policyId = insertAndReturnId("""
				INSERT INTO public.attendance_policy_version (
				    department_id, version_no, name, check_in_start_time,
				    status, created_by_account_id, published_by_account_id,
				    published_at)
				VALUES (?, 1, '운영 집계 정책', TIME '08:30', 'PUBLISHED', ?, ?,
				        CURRENT_TIMESTAMP)
				RETURNING id
				""", departmentId, systemAccountId, systemAccountId);
		jdbcTemplate.update("""
				INSERT INTO public.attendance_band (
				    policy_version_id, sequence_no, label, parent_status, upper_time)
				VALUES
				    (?, 1, '정상 출석', 'PRESENT', TIME '09:00'),
				    (?, 2, '1차 지각', 'LATE', TIME '09:15')
				""", policyId, policyId);
		jdbcTemplate.update("""
				INSERT INTO public.attendance_day (
				    department_id, attendance_date, policy_version_id, status,
				    created_by_account_id, finalization_due_at)
				VALUES (?, CURRENT_DATE, ?, 'SCHEDULED', ?,
				        CURRENT_TIMESTAMP - INTERVAL '1 minute')
				""", departmentId, policyId, systemAccountId);

		Map<String, Object> operations = administrationService.operations(
				new AccountActor(systemAccountId));
		assertThat(((Number) operations.get("overdue_day_count")).longValue())
				.isEqualTo(1L);

		AccountPrincipal systemPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername(SYSTEM_USERNAME);
		mockMvc.perform(get("/admin/system/operations")
						.with(user(systemPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"마감 시각 경과·미마감 출석 날짜")));
	}

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
		jdbcTemplate.update("DELETE FROM public.attendance_policy_schedule_weekday");
		jdbcTemplate.update("DELETE FROM public.attendance_policy_schedule_monthday");
		jdbcTemplate.update("DELETE FROM public.attendance_policy_schedule");
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

		mockMvc.perform(get("/"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin"));
		mockMvc.perform(get("/login"))
				.andExpect(status().isOk())
				.andExpect(view().name("login"));
		mockMvc.perform(get("/js/admin.js"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/admin"))
				.andExpect(status().is3xxRedirection());
		mockMvc.perform(get("/admin/account/notifications")
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/account-notifications"))
				.andExpect(content().string(containsString("Telegram 알림 기능이 현재 비활성화")));
		mockMvc.perform(post("/admin/account/notifications/telegram/link")
						.with(user(departmentPrincipal)))
				.andExpect(status().isForbidden());

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
		mockMvc.perform(get("/admin/system/operations")
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
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/attendance-days")
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString(
						"const recurrenceInputs"))));
		mockMvc.perform(get("/admin/departments/" + departmentId + "/history")
						.with(user(departmentPrincipal)))
				.andExpect(content().string(not(containsString("태깅 이력"))));

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
						+ "/policies")
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"마감 시간 (마지막 태깅 허용 시각)")))
				.andExpect(content().string(containsString(
						"반복 방식을 먼저 선택하면 필요한 설정만 표시됩니다.")))
				.andExpect(content().string(containsString(
						"/js/policy-form.js")))
				.andExpect(content().string(not(containsString(
						"name=\"recurrencePattern\" value=\"YEARLY\""))));
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/policies/" + policyId)
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"마감 시간 (마지막 태깅 허용 시각)")))
				.andExpect(content().string(containsString(
						"1µs 후 미출석자는 결석 처리 대상")))
				.andExpect(content().string(containsString(
						"updateEditFinalizationLabels")));
		attendancePolicyService.publish(departmentActor, departmentId, policyId);
		LocalDate today = LocalDate.now(clock);
		long dashboardMemberId = insertAndReturnId("""
				INSERT INTO public.member(name, birth, active)
				VALUES ('대시보드 교사', DATE '1992-03-04', TRUE)
				RETURNING id
				""");
		jdbcTemplate.update("""
				INSERT INTO public.department_membership(
				    department_id, member_id, joined_at, created_by_account_id)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?)
				""", departmentId, dashboardMemberId, departmentAccountId);
		long dayId = attendanceDayService.createDay(
				departmentActor,
				departmentId,
				today.plusDays(1),
				policyId);
		jdbcTemplate.update("""
				UPDATE public.attendance_day
				SET attendance_date = ?
				WHERE id = ?
				""", today, dayId);
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/dashboard-data")
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.attendance_day_id")
						.value(dayId))
				.andExpect(jsonPath("$.summary.pending_count").value(1))
				.andExpect(jsonPath("$.rows[0].memberId")
						.value(dashboardMemberId))
				.andExpect(jsonPath("$.rows[0].name")
						.value("대시보드 교사"))
				.andExpect(jsonPath("$.rows[0].addedSource").doesNotExist())
				.andExpect(jsonPath("$.rows[0].recordId").doesNotExist())
				.andExpect(jsonPath("$.rows[0].source").doesNotExist())
				.andExpect(jsonPath("$.rows[0].note").doesNotExist());
		mockMvc.perform(get("/admin/departments/" + departmentId)
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(
						containsString("data-attendance-filter")));
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

	/** 반복 생성은 JavaScript 없이 동작하고 잘못된 입력 뒤에도 form을 보존한다. */
	@Test
	void createsRecurringDaysWithoutJavaScriptAndPreservesInvalidInput()
			throws Exception {
		AccountPrincipal departmentPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername(DEPARTMENT_USERNAME);
		var actor = new com.example.attend.access.api.AccountActor(departmentAccountId);
		long policyId = attendancePolicyService.createDraft(
				actor,
				departmentId,
				new PolicyDraftCommand(
						"반복 화면 정책",
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
		attendancePolicyService.publish(actor, departmentId, policyId);

		String path = "/admin/departments/" + departmentId + "/attendance-days";
		mockMvc.perform(get(path).with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"const recurrenceInputs")))
				.andExpect(content().string(containsString(
						"<section id=\"recurrenceOptions\">")))
				.andExpect(content().string(not(containsString(
						"id=\"recurrenceOptions\" hidden"))))
				.andExpect(content().string(not(containsString(
						"name=\"endDate\" disabled"))))
				.andExpect(content().string(containsString(
						"선택한 반복 방식에 따라 일·주·개월·년 단위로 적용됩니다.")))
				.andExpect(content().string(not(containsString(">일</span>마다"))));

		LocalDate firstDate = LocalDate.now(clock).plusDays(1);
		mockMvc.perform(post(path)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("attendanceDate", firstDate.toString())
						.param("endDate", firstDate.plusDays(2).toString())
						.param("policyVersionId", Long.toString(policyId))
						.param("recurrence", "DAILY")
						.param("interval", "1")
						.param("weeklyDays", "MONDAY")
						.param("monthlyDays", "1")
						.param("yearlyMonth", "1")
						.param("yearlyDay", "1"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(path))
				.andExpect(flash().attribute(
						"message", "출석 날짜 3건을 생성했습니다."));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.attendance_day
				WHERE department_id = ?
				  AND attendance_date BETWEEN ? AND ?
				""", Integer.class, departmentId, firstDate, firstDate.plusDays(2)))
				.isEqualTo(3);

		LocalDate invalidStart = firstDate.plusDays(10);
		MvcResult validationFailure = mockMvc.perform(post(path)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("attendanceDate", invalidStart.toString())
						.param("endDate", invalidStart.plusDays(20).toString())
						.param("policyVersionId", Long.toString(policyId))
						.param("recurrence", "WEEKLY")
						.param("interval", "2")
						.param("yearlyMonth", "4")
						.param("yearlyDay", "5"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(path))
				.andExpect(flash().attribute(
						"error", "주간 반복 요일을 하나 이상 선택하세요."))
				.andReturn();
		Object submittedForm = validationFailure.getFlashMap().get("attendanceDayForm");
		assertThat(submittedForm).isInstanceOf(Map.class);
		Map<?, ?> formValues = (Map<?, ?>) submittedForm;
		assertThat(formValues.get("attendanceDate")).isEqualTo(invalidStart);
		assertThat(formValues.get("endDate")).isEqualTo(invalidStart.plusDays(20));
		assertThat(formValues.get("policyVersionId")).isEqualTo(policyId);
		assertThat(formValues.get("recurrence")).isEqualTo("WEEKLY");
		assertThat(formValues.get("interval")).isEqualTo(2);
		mockMvc.perform(get(path)
						.with(user(departmentPrincipal))
						.flashAttrs(validationFailure.getFlashMap()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(invalidStart.toString())))
				.andExpect(content().string(containsString(
						invalidStart.plusDays(20).toString())))
				.andExpect(content().string(containsString(
						"주간 반복 요일을 하나 이상 선택하세요.")));

		for (Map.Entry<String, String> malformed : Map.of(
				"WEEKLY", "weeklyDays",
				"MONTHLY", "monthlyDays").entrySet()) {
			mockMvc.perform(post(path)
							.with(user(departmentPrincipal))
							.with(csrf())
							.param("attendanceDate", invalidStart.toString())
							.param("endDate", invalidStart.plusDays(20).toString())
							.param("policyVersionId", Long.toString(policyId))
							.param("recurrence", malformed.getKey())
							.param("interval", "1")
							.param(malformed.getValue(), ",")
							.param("yearlyMonth", "1")
							.param("yearlyDay", "1"))
					.andExpect(status().is3xxRedirection())
					.andExpect(redirectedUrl(path))
					.andExpect(flash().attribute("error",
							malformed.getKey().equals("WEEKLY")
									? "반복 요일 값이 올바르지 않습니다."
									: "반복 날짜 값이 올바르지 않습니다."));
		}

		mockMvc.perform(post(path)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("attendanceDate", invalidStart.toString())
						.param("endDate", invalidStart.plusDays(20).toString())
						.param("policyVersionId", Long.toString(policyId))
						.param("recurrence", "YEARLY")
						.param("interval", Integer.toString(Integer.MAX_VALUE))
						.param("yearlyMonth", "1")
						.param("yearlyDay", "1"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(path))
				.andExpect(flash().attribute(
						"error", "반복 간격이 허용 범위를 초과했습니다."));
	}

	/**
	 * 미등록 카드 원본 UID를 HTML에 노출하지 않고 부서 교사에게 연결하는지 확인한다.
	 *
	 * @throws Exception MockMvc 요청 처리에 실패한 경우
	 */
	@Test
	void connectsInboxCardWithoutRenderingRawUid() throws Exception {
		AccountPrincipal departmentPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername(DEPARTMENT_USERNAME);
		long memberId = insertAndReturnId("""
				INSERT INTO public.member(name, birth, active)
				VALUES (?, DATE '1990-03-15', TRUE)
				RETURNING id
				""", "카드 대기 교사");
		jdbcTemplate.update("""
				INSERT INTO public.department_membership(
				    department_id, member_id, joined_at, created_by_account_id)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?)
				""", departmentId, memberId, departmentAccountId);
		long deviceId = insertAndReturnId("""
				INSERT INTO public.device(
				    department_id, device_code, name, credential_hash)
				VALUES (?, 'm3-inbox-device', 'M3 카드 장치', 'test-hash')
				RETURNING id
				""", departmentId);
		long eventId = insertAndReturnId("""
				INSERT INTO public.tag_event_log(
				    device_id, department_id, request_id, uid, result_code,
				    http_status, response_body, failure_type)
				VALUES (?, ?, 'm3-inbox-event', '04ABCDEF', 'UNKNOWN_UID',
				        404, '{"code":"UNKNOWN_UID"}'::jsonb, 'UNKNOWN_UID')
				RETURNING id
				""", deviceId, departmentId);
		long expiredEventId = insertAndReturnId("""
				INSERT INTO public.tag_event_log(
				    device_id, department_id, request_id, uid, result_code,
				    http_status, response_body, failure_type)
				VALUES (?, ?, 'm3-expired-inbox-event', '04AAAAAA', 'UNKNOWN_UID',
				        404, '{"code":"UNKNOWN_UID"}'::jsonb, 'UNKNOWN_UID')
				RETURNING id
				""", deviceId, departmentId);
		jdbcTemplate.update("""
				UPDATE public.tag_event_log
				SET received_at = CURRENT_TIMESTAMP - INTERVAL '8 days'
				WHERE id = ?
				""", expiredEventId);

		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/teachers")
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("현재 멤버")))
				.andExpect(content().string(containsString(
						"type=\"date\" name=\"birth\" required")))
				.andExpect(content().string(containsString("max=\"")))
				.andExpect(content().string(containsString("data-row-href")));
		mockMvc.perform(post("/admin/departments/" + departmentId
						+ "/teachers")
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("name", "생년월일 누락 교사"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/departments/" + departmentId
						+ "/teachers"))
				.andExpect(flash().attribute(
						"error", "생년월일은 필수입니다."));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.member
				WHERE name = '생년월일 누락 교사'
				""", Integer.class)).isZero();
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/teachers/" + memberId)
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(">수정</a>")))
				.andExpect(content().string(containsString("data-attendance-chart")))
				.andExpect(content().string(not(containsString("기본정보 저장"))));
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/teachers/" + memberId
						+ "?fromDate=2026-01-01&toDate=2026-06-30")
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"value=\"2026-01-01\"")))
				.andExpect(content().string(containsString(
						"value=\"2026-06-30\"")))
				.andExpect(content().string(containsString(
						"2026-01-01 ~ 2026-06-30")));
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/teachers/" + memberId
						+ "?fromDate=2026-06-30&toDate=2026-01-01")
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"시작일은 종료일보다 늦을 수 없습니다.")))
				.andExpect(content().string(containsString(
						"value=\"2026-06-30\"")))
				.andExpect(content().string(containsString(
						"value=\"2026-01-01\"")));
		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/teachers/" + memberId + "?edit=true")
						.with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("수정 모드")))
				.andExpect(content().string(containsString("카드 연결")));
		String updatePath = "/admin/departments/" + departmentId
				+ "/teachers/" + memberId + "/update";
		String teacherPath = "/admin/departments/" + departmentId
				+ "/teachers/" + memberId;
		mockMvc.perform(post(updatePath)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("name", "카드 대기 교사"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(teacherPath))
				.andExpect(flash().attribute(
						"error", "생년월일은 필수입니다."));
		mockMvc.perform(post(updatePath)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("name", "카드 대기 교사")
						.param("birth", "2999-01-01"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(teacherPath))
				.andExpect(flash().attribute(
						"error", "생년월일은 미래일 수 없습니다."));
		mockMvc.perform(post(updatePath)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("name", "카드 대기 교사 수정")
						.param("birth", "1991-04-16"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(teacherPath))
				.andExpect(flash().attribute(
						"message", "교사 정보를 수정했습니다."));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.audit_log
				WHERE department_id = ?
				  AND action = 'TEACHER_UPDATED'
				  AND after_data -> 'updatedFields' = '["name", "birth"]'::jsonb
				  AND coalesce(before_data::text, '') NOT LIKE '%카드 대기 교사%'
				  AND coalesce(after_data::text, '') NOT LIKE '%카드 대기 교사%'
				  AND coalesce(before_data::text, '') NOT LIKE '%1990-03-15%'
				  AND coalesce(after_data::text, '') NOT LIKE '%1991-04-16%'
				""", Integer.class, departmentId)).isEqualTo(1);

		mockMvc.perform(get("/admin/departments/" + departmentId
						+ "/cards/inbox")
						.with(user(departmentPrincipal)))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("****CDEF")))
					.andExpect(content().string(not(containsString("****AAAA"))))
					.andExpect(content().string(not(containsString("04ABCDEF"))));

		mockMvc.perform(post("/admin/departments/" + departmentId
						+ "/cards/inbox/" + expiredEventId + "/connect")
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("memberId", Long.toString(memberId)))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/departments/" + departmentId
						+ "/cards/inbox"))
				.andExpect(flash().attribute("error",
						"카드 등록 요청이 이미 처리되었거나 더 이상 사용할 수 없습니다."));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.nfc_card
				WHERE uid = '04AAAAAA'
					""", Integer.class)).isZero();

		mockMvc.perform(post("/admin/departments/" + departmentId
						+ "/cards/inbox/" + eventId + "/connect")
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("memberId", Long.toString(memberId)))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/departments/" + departmentId
						+ "/cards/inbox"));
		mockMvc.perform(post("/admin/departments/" + departmentId
						+ "/cards/inbox/" + eventId + "/connect")
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("memberId", Long.toString(memberId)))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/departments/" + departmentId
						+ "/cards/inbox"))
				.andExpect(flash().attribute("error",
						"카드 등록 요청이 이미 처리되었거나 더 이상 사용할 수 없습니다."));

		long otherDepartmentId = insertAndReturnId(
				"INSERT INTO public.department(name) VALUES (?) RETURNING id",
				"카드 경계 부서");
		long otherDeviceId = insertAndReturnId("""
				INSERT INTO public.device(
				    department_id, device_code, name, credential_hash)
				VALUES (?, 'm3-other-inbox-device', '다른 부서 장치', 'test-hash')
				RETURNING id
				""", otherDepartmentId);
		long otherEventId = insertAndReturnId("""
				INSERT INTO public.tag_event_log(
				    device_id, department_id, request_id, uid, result_code,
				    http_status, response_body, failure_type)
				VALUES (?, ?, 'm3-other-inbox-event', '04FEDCBA', 'UNKNOWN_UID',
				        404, '{"code":"UNKNOWN_UID"}'::jsonb, 'UNKNOWN_UID')
				RETURNING id
				""", otherDeviceId, otherDepartmentId);
		mockMvc.perform(post("/admin/departments/" + departmentId
						+ "/cards/inbox/" + otherEventId + "/connect")
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("memberId", Long.toString(memberId)))
				.andExpect(status().isNotFound());

		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.nfc_card_assignment AS assignment
				JOIN public.nfc_card AS card ON card.id = assignment.nfc_card_id
				WHERE assignment.department_id = ?
				  AND assignment.member_id = ?
				  AND assignment.unassigned_at IS NULL
				  AND card.uid = '04ABCDEF'
				  AND card.status = 'ACTIVE'
					""", Integer.class, departmentId, memberId)).isEqualTo(1);
	}

	/**
	 * 부서 제외 확인 화면에서 본 미래 대상 식별자 집합이 바뀌면 재확인을 요구하고,
	 * 확인한 현재 대상 전체만 소속·카드와 같은 트랜잭션에서 제외하는지 검증한다.
	 */
	@Test
	void excludesTeacherAndAllEligibleFutureTargetsOnlyAfterExplicitConfirmation()
			throws Exception {
		AccountPrincipal departmentPrincipal = (AccountPrincipal)
				userDetailsService.loadUserByUsername(DEPARTMENT_USERNAME);
		var actor = new com.example.attend.access.api.AccountActor(departmentAccountId);
		long memberId = insertAndReturnId("""
				INSERT INTO public.member(name, birth, active)
				VALUES ('미래 대상 교사', DATE '1993-04-05', TRUE)
				RETURNING id
				""");
		jdbcTemplate.update("""
				INSERT INTO public.department_membership(
				    department_id, member_id, joined_at, created_by_account_id)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?)
				""", departmentId, memberId, departmentAccountId);
		long policyId = attendancePolicyService.createDraft(
				actor,
				departmentId,
				new PolicyDraftCommand(
						"미래 대상 정책",
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
		attendancePolicyService.publish(actor, departmentId, policyId);
		LocalDate futureDate = LocalDate.now(clock).plusDays(1);
		long dayId = attendanceDayService.createDay(
				actor, departmentId, futureDate, policyId);

		String exclusionPath = "/admin/departments/" + departmentId
				+ "/teachers/" + memberId + "/exclude";
		mockMvc.perform(get(exclusionPath).with(user(departmentPrincipal)))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/department/teacher-exclude"))
				.andExpect(content().string(containsString("미래 대상 교사")))
				.andExpect(content().string(containsString(futureDate.toString())))
				.andExpect(content().string(containsString(
						"미래 출석일 1건에서도 제외됩니다.")))
				.andExpect(content().string(containsString(
						"name=\"expectedFutureAttendanceDayIds\"")))
				.andExpect(content().string(containsString(
						"value=\"" + dayId + "\"")))
				.andExpect(content().string(not(containsString(
						"name=\"expectedFutureAttendanceDayCount\""))));

		mockMvc.perform(post(exclusionPath)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("cardDisposition", "AVAILABLE")
						.param("reason", "사역 종료"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(exclusionPath))
				.andExpect(flash().attribute(
						"error", "부서 제외 영향을 확인해야 합니다."));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.department_membership
				WHERE department_id = ?
				  AND member_id = ?
				  AND ended_at IS NULL
				""", Integer.class, departmentId, memberId)).isEqualTo(1);

		for (String malformedExpectedIds : List.of(",", "1,")) {
			mockMvc.perform(post(exclusionPath)
							.with(user(departmentPrincipal))
							.with(csrf())
							.param("expectedFutureAttendanceDayIds", malformedExpectedIds)
							.param("cardDisposition", "AVAILABLE")
							.param("reason", "사역 종료")
							.param("confirmImpact", "true"))
					.andExpect(status().is3xxRedirection())
					.andExpect(redirectedUrl(exclusionPath))
					.andExpect(flash().attribute(
							"error",
							"expected future attendance day ids must be positive"));
		}
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.department_membership
				WHERE department_id = ?
				  AND member_id = ?
				  AND ended_at IS NULL
				""", Integer.class, departmentId, memberId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT is_target
				FROM public.attendance_target
				WHERE attendance_day_id = ?
				  AND member_id = ?
				""", Boolean.class, dayId, memberId)).isTrue();

		jdbcTemplate.update("""
				UPDATE public.attendance_target
				SET is_target = FALSE,
					changed_by_account_id = ?,
					changed_at = CURRENT_TIMESTAMP,
					change_reason = '확인 후 대상 교체'
				WHERE attendance_day_id = ?
				  AND member_id = ?
				""", departmentAccountId, dayId, memberId);
		LocalDate secondFutureDate = futureDate.plusDays(1);
		long secondDayId = attendanceDayService.createDay(
				actor, departmentId, secondFutureDate, policyId);
		mockMvc.perform(post(exclusionPath)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("expectedFutureAttendanceDayIds", Long.toString(dayId))
						.param("cardDisposition", "AVAILABLE")
						.param("reason", "사역 종료")
						.param("confirmImpact", "true"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(exclusionPath))
				.andExpect(flash().attribute(
						"error",
						"미래 출석일 대상이 변경되었습니다. 현재 영향을 다시 확인해 주세요."));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.department_membership
				WHERE department_id = ?
				  AND member_id = ?
				  AND ended_at IS NULL
				""", Integer.class, departmentId, memberId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.attendance_target
				WHERE attendance_day_id IN (?, ?)
				  AND member_id = ?
				  AND is_target
				""", Integer.class, dayId, secondDayId, memberId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT is_target
				FROM public.attendance_target
				WHERE attendance_day_id = ?
				  AND member_id = ?
				""", Boolean.class, secondDayId, memberId)).isTrue();

		mockMvc.perform(post(exclusionPath)
						.with(user(departmentPrincipal))
						.with(csrf())
						.param("expectedFutureAttendanceDayIds", Long.toString(secondDayId))
						.param("cardDisposition", "AVAILABLE")
						.param("reason", "사역 종료")
						.param("confirmImpact", "true"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl(
						"/admin/departments/" + departmentId + "/teachers"))
				.andExpect(flash().attribute(
						"message", "교사를 부서에서 제외했습니다."));

		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.attendance_target
				WHERE attendance_day_id IN (?, ?)
				  AND member_id = ?
				  AND is_target
				""", Integer.class, dayId, secondDayId, memberId)).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM public.department_membership
				WHERE department_id = ?
				  AND member_id = ?
				  AND ended_at IS NULL
				""", Integer.class, departmentId, memberId)).isZero();
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
