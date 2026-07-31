package com.example.attend.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.attend.access.api.AccountActor;
import com.example.attend.device.application.DeviceManagementService;
import com.example.attend.device.application.IssuedDeviceCredential;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M4 장치 수명주기와 HTTP 계약의 대표 흐름을 실제 PostgreSQL로 검증한다.
 *
 * <p>최소 테스트 원칙에 따라 모든 조합을 반복하지 않고, 상태 전이·멱등 저장·strict
 * 입력 경계를 실패 가능성이 큰 한 시나리오에 집중한다.</p>
 */
@SpringBootTest(properties = {
		"attendance.admin.write-enabled=true",
		"device-api.enabled=true",
		"device-api.credential-pepper="
				+ "separate-device-test-pepper-at-least-32-bytes"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(DeviceApiIntegrationTest.FixedClockConfiguration.class)
class DeviceApiIntegrationTest {

	private static final Instant RECEIVED_AT =
			Instant.parse("2026-08-02T00:00:00Z");

	/** Spring Boot가 migration과 업무 SQL을 실행할 PostgreSQL 15 서버다. */
	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:15-alpine");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private DeviceManagementService deviceManagementService;

	private long departmentId;
	private long systemAccountId;
	private IssuedDeviceCredential issued;

	/** 각 테스트가 독립된 부서·관리자·교사·카드·정책·장치를 사용하게 만든다. */
	@BeforeEach
	void setUp() {
		deleteFixtures();
		departmentId = insertId(
				"INSERT INTO public.department(name) VALUES (?) RETURNING id",
				"아동부");
		systemAccountId = insertId("""
				INSERT INTO public.account(
				    username,
				    password_hash,
				    system_role,
				    status,
				    password_changed_at)
				VALUES ('m4-system', 'not-used-by-this-test', 'SYSTEM_ADMIN',
				        'ACTIVE', ?)
				RETURNING id
				""", OffsetDateTime.ofInstant(RECEIVED_AT, ZoneId.of("Asia/Seoul")));
		long memberId = insertId("""
				INSERT INTO public.member(name, active)
				VALUES ('장치 시험 교사', TRUE)
				RETURNING id
				""");
		long membershipId = insertId("""
				INSERT INTO public.department_membership(
				    department_id, member_id, joined_at, created_by_account_id)
				VALUES (?, ?, ?, ?)
				RETURNING id
				""", departmentId, memberId, atSeoul(RECEIVED_AT.minusSeconds(3600)),
				systemAccountId);
		long cardId = insertId("""
				INSERT INTO public.nfc_card(uid, status)
				VALUES ('04A1B2C3', 'ACTIVE')
				RETURNING id
				""");
		jdbcTemplate.update("""
				INSERT INTO public.nfc_card_assignment(
				    nfc_card_id, department_id, membership_id, member_id,
				    assigned_by_account_id, assigned_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""", cardId, departmentId, membershipId, memberId,
				systemAccountId, atSeoul(RECEIVED_AT.minusSeconds(1800)));
		long policyId = insertId("""
				INSERT INTO public.attendance_policy_version(
				    department_id, version_no, name, check_in_start_time,
				    status, created_by_account_id, published_by_account_id,
				    published_at)
				VALUES (?, 1, '장치 시험 정책', '08:30', 'PUBLISHED', ?, ?, ?)
				RETURNING id
				""", departmentId, systemAccountId, systemAccountId,
				atSeoul(RECEIVED_AT.minusSeconds(7200)));
		jdbcTemplate.update("""
				INSERT INTO public.attendance_band(
				    policy_version_id, sequence_no, label, parent_status, upper_time)
				VALUES
				    (?, 1, '정상 출석', 'PRESENT', '09:00'),
				    (?, 2, '1차 지각', 'LATE', '09:20')
				""", policyId, policyId);
		long dayId = insertId("""
				INSERT INTO public.attendance_day(
				    department_id, attendance_date, policy_version_id,
				    status, created_by_account_id)
				VALUES (?, ?, ?, 'SCHEDULED', ?)
				RETURNING id
				""", departmentId, LocalDate.of(2026, 8, 2),
				policyId, systemAccountId);
		jdbcTemplate.update("""
				INSERT INTO public.attendance_target(
				    attendance_day_id, member_id, department_id, membership_id)
				VALUES (?, ?, ?, ?)
				""", dayId, memberId, departmentId, membershipId);
		issued = deviceManagementService.create(
				new AccountActor(systemAccountId),
				departmentId,
				"entrance-01",
				"입구 장치");
	}

	/**
	 * 시험 전 차단부터 최초 출석과 정확한 멱등 replay까지 한 흐름으로 검증한다.
	 */
	@Test
	void completesCredentialActivationAndIdempotentCheckIn() throws Exception {
		String payload = """
				{"uid":"04A1B2C3","requestId":"boot_A1-1"}
				""";

		mockMvc.perform(authenticatedPost("/api/v1/device/check-ins")
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"))
				.andExpect(jsonPath("$.requestId").isEmpty());
		assertThat(count("public.tag_event_log")).isZero();

		mockMvc.perform(authenticatedPost("/api/v1/device/credential-tests"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("CREDENTIAL_VALID"))
				.andExpect(jsonPath("$.data.deviceStatus").value("INACTIVE"))
				.andExpect(jsonPath("$.data.credentialVersion").value(1));
		deviceManagementService.activate(
				new AccountActor(systemAccountId), issued.deviceId());

		MvcResult first = mockMvc.perform(
						authenticatedPost("/api/v1/device/check-ins")
								.contentType(MediaType.APPLICATION_JSON)
								.content(payload))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("CHECKED_IN"))
				.andExpect(jsonPath("$.data.attendanceStatus").value("PRESENT"))
				.andReturn();
		MvcResult replay = mockMvc.perform(
						authenticatedPost("/api/v1/device/check-ins")
								.contentType(MediaType.APPLICATION_JSON)
								.content(payload))
				.andExpect(status().isCreated())
				.andReturn();

		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(first.getResponse().getContentAsString());
		assertThat(count("public.attendance_record")).isEqualTo(1);
		assertThat(count("public.tag_event_log")).isEqualTo(1);

		mockMvc.perform(authenticatedPost("/api/v1/device/check-ins")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uid":"04FFFFFF","requestId":"boot_A1-1"}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
		assertThat(count("public.tag_event_log")).isEqualTo(1);
	}

	/** 중복 JSON member와 실제 1025-byte body를 event 생성 전에 거부한다. */
	@Test
	void rejectsDuplicateJsonFieldsAndOversizedBodiesBeforeEvents() throws Exception {
		mockMvc.perform(authenticatedPost("/api/v1/device/credential-tests"))
				.andExpect(status().isOk());
		deviceManagementService.activate(
				new AccountActor(systemAccountId), issued.deviceId());

		mockMvc.perform(authenticatedPost("/api/v1/device/check-ins")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uid":"04A1B2C3","uid":"04FFFFFF","requestId":"dup-1"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		mockMvc.perform(authenticatedPost("/api/v1/device/check-ins")
						.contentType(MediaType.APPLICATION_JSON)
						.content(" ".repeat(1025)))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
		assertThat(count("public.tag_event_log")).isZero();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
			authenticatedPost(String path) {
		return post(path)
				.header("X-Device-Code", issued.deviceCode())
				.header("X-Device-Key", issued.deviceKey());
	}

	private int count(String table) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM " + table, Integer.class);
		return count == null ? 0 : count;
	}

	private long insertId(String sql, Object... arguments) {
		Long id = jdbcTemplate.queryForObject(sql, Long.class, arguments);
		if (id == null) {
			throw new IllegalStateException("test fixture insert returned no id");
		}
		return id;
	}

	private static OffsetDateTime atSeoul(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneId.of("Asia/Seoul"));
	}

	private void deleteFixtures() {
		for (String table : new String[]{
				"audit_log", "tag_event_log", "attendance_record",
				"attendance_target", "attendance_day", "attendance_band",
				"attendance_policy_version", "nfc_card_assignment", "nfc_card",
				"department_membership", "device", "account_department_role",
				"account_credential_token", "account", "department", "member"}) {
			jdbcTemplate.execute("DELETE FROM public." + table);
		}
	}

	/**
	 * 출석 판정이 항상 2026-08-02 09:00 Asia/Seoul을 사용하게 고정한다.
	 */
	@TestConfiguration
	static class FixedClockConfiguration {

		/** 운영 Clock 대신 테스트 고정 시계를 우선 주입한다. */
		@Bean
		@Primary
		Clock fixedAttendanceClock() {
			return Clock.fixed(RECEIVED_AT, ZoneId.of("Asia/Seoul"));
		}
	}
}
