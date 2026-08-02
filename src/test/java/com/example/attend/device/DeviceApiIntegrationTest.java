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
import java.util.concurrent.atomic.AtomicReference;
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
@Import(DeviceApiIntegrationTest.TestClockConfiguration.class)
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

	@Autowired
	private MutableTestClock testClock;

	private long departmentId;
	private long systemAccountId;
	private IssuedDeviceCredential issued;

	/** 각 테스트가 독립된 부서·관리자·교사·카드·정책·장치를 사용하게 만든다. */
	@BeforeEach
	void setUp() {
		testClock.setInstant(RECEIVED_AT);
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
		Long firstRecordId = jdbcTemplate.queryForObject("""
				SELECT id
				FROM public.attendance_record
				""", Long.class);
		OffsetDateTime firstCheckedInAt = jdbcTemplate.queryForObject("""
				SELECT checked_in_at
				FROM public.attendance_record
				""", OffsetDateTime.class);
		assertThat(firstCheckedInAt).isEqualTo(atSeoul(RECEIVED_AT));

		mockMvc.perform(authenticatedPost("/api/v1/device/check-ins")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uid":"04FFFFFF","requestId":"boot_A1-1"}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
		assertThat(count("public.tag_event_log")).isEqualTo(1);

		/* 덮어쓰기 버그가 같은 고정 시각에 가려지지 않도록 재태깅 시각을 전진시킨다. */
		testClock.setInstant(RECEIVED_AT.plusSeconds(300));
		mockMvc.perform(authenticatedPost("/api/v1/device/check-ins")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uid":"04A1B2C3","requestId":"boot_A1-2"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
		assertThat(count("public.attendance_record")).isEqualTo(1);
		assertThat(count("public.tag_event_log")).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT id
				FROM public.attendance_record
				""", Long.class)).isEqualTo(firstRecordId);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT checked_in_at
				FROM public.attendance_record
				""", OffsetDateTime.class)).isEqualTo(firstCheckedInAt);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT result_code = 'ALREADY_CHECKED_IN'
				       AND attendance_record_id = ?
				FROM public.tag_event_log
				WHERE device_id = ?
				  AND request_id = 'boot_A1-2'
				""", Boolean.class, firstRecordId, issued.deviceId())).isTrue();
	}

	/**
	 * 서로 다른 장치의 code/key 조합과 다른 부서 카드가 인증·부서 경계를 넘지 못함을
	 * 검증한다.
	 */
	@Test
	void rejectsMixedCredentialsAndCrossDepartmentCardWithoutDisclosure()
			throws Exception {
		long otherDepartmentId = insertId(
				"INSERT INTO public.department(name) VALUES (?) RETURNING id",
				"중고등부");
		long otherMemberId = insertId("""
				INSERT INTO public.member(name, active)
				VALUES ('다른 부서 교사', TRUE)
				RETURNING id
				""");
		long otherMembershipId = insertId("""
				INSERT INTO public.department_membership(
				    department_id, member_id, joined_at, created_by_account_id)
				VALUES (?, ?, ?, ?)
				RETURNING id
				""", otherDepartmentId, otherMemberId,
				atSeoul(RECEIVED_AT.minusSeconds(3600)), systemAccountId);
		long otherCardId = insertId("""
				INSERT INTO public.nfc_card(uid, status)
				VALUES ('04D4E5F6', 'ACTIVE')
				RETURNING id
				""");
		jdbcTemplate.update("""
				INSERT INTO public.nfc_card_assignment(
				    nfc_card_id, department_id, membership_id, member_id,
				    assigned_by_account_id, assigned_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""", otherCardId, otherDepartmentId, otherMembershipId,
				otherMemberId, systemAccountId,
				atSeoul(RECEIVED_AT.minusSeconds(1800)));
		IssuedDeviceCredential otherIssued = deviceManagementService.create(
				new AccountActor(systemAccountId),
				otherDepartmentId,
				"entrance-02",
				"다른 부서 입구 장치");

		MvcResult mixedCredentialResponse = mockMvc.perform(
						post("/api/v1/device/check-ins")
								.header("X-Device-Code", issued.deviceCode())
								.header("X-Device-Key", otherIssued.deviceKey())
								.contentType(MediaType.APPLICATION_JSON)
								.content("""
										{"uid":"04A1B2C3","requestId":"mixed-auth-1"}
										"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("DEVICE_UNAUTHORIZED"))
				.andExpect(jsonPath("$.message").value("장치 인증에 실패했습니다."))
				.andExpect(jsonPath("$.requestId").isEmpty())
				.andExpect(jsonPath("$.data").isEmpty())
				.andReturn();
		assertThat(mixedCredentialResponse.getResponse().getContentAsString())
				.doesNotContain("entrance-01", "entrance-02", "중고등부");
		assertThat(count("public.tag_event_log")).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) = 2
				FROM public.device
				WHERE id IN (?, ?)
				  AND last_seen_at IS NULL
				""", Boolean.class, issued.deviceId(), otherIssued.deviceId()))
				.isTrue();

		mockMvc.perform(authenticatedPost("/api/v1/device/credential-tests"))
				.andExpect(status().isOk());
		deviceManagementService.activate(
				new AccountActor(systemAccountId), issued.deviceId());

		MvcResult crossDepartmentResponse = mockMvc.perform(
						authenticatedPost("/api/v1/device/check-ins")
								.contentType(MediaType.APPLICATION_JSON)
								.content("""
										{"uid":"04D4E5F6","requestId":"cross-department-1"}
										"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("NOT_DEPARTMENT_MEMBER"))
				.andExpect(jsonPath("$.data").isEmpty())
				.andExpect(jsonPath("$.departmentId").doesNotExist())
				.andExpect(jsonPath("$.memberId").doesNotExist())
				.andExpect(jsonPath("$.memberName").doesNotExist())
				.andExpect(jsonPath("$.membershipId").doesNotExist())
				.andExpect(jsonPath("$.cardId").doesNotExist())
				.andExpect(jsonPath("$.uid").doesNotExist())
				.andReturn();
		assertThat(crossDepartmentResponse.getResponse().getContentAsString())
				.doesNotContain("중고등부", "다른 부서 교사", "04D4E5F6");
		assertThat(count("public.attendance_record")).isZero();
		assertThat(count("public.tag_event_log")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT department_id = ?
				       AND result_code = 'NOT_DEPARTMENT_MEMBER'
				       AND attendance_day_id IS NULL
				       AND attendance_record_id IS NULL
				FROM public.tag_event_log
				WHERE device_id = ?
				  AND request_id = 'cross-department-1'
				""", Boolean.class, departmentId, issued.deviceId())).isTrue();
	}

	/** 중복 JSON member와 실제 1025-byte body를 event 생성 전에 거부한다. */
	@Test
	void rejectsDuplicateJsonFieldsAndOversizedBodiesBeforeEvents() throws Exception {
		mockMvc.perform(post("/api/v1/device/check-ins")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uid":"04A1B2C3","requestId":"missing-auth"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("DEVICE_UNAUTHORIZED"));
		mockMvc.perform(post("/api/v1/device/check-ins")
						.header(
								"X-Device-Code",
								issued.deviceCode(),
								issued.deviceCode())
						.header("X-Device-Key", issued.deviceKey())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uid":"04A1B2C3","requestId":"duplicate-auth"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("DEVICE_UNAUTHORIZED"));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT last_seen_at IS NULL
				FROM public.device
				WHERE id = ?
				""", Boolean.class, issued.deviceId())).isTrue();

		mockMvc.perform(authenticatedPost("/api/v1/device/credential-tests")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNEXPECTED_BODY"));
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

	/** 현재 fixture의 code/key header를 포함한 POST 요청 builder를 만든다. */
	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
			authenticatedPost(String path) {
		return post(path)
				.header("X-Device-Code", issued.deviceCode())
				.header("X-Device-Key", issued.deviceKey());
	}

	/** allowlist에서 선택한 fixture 테이블의 현재 행 수를 조회한다. */
	private int count(String table) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM " + table, Integer.class);
		return count == null ? 0 : count;
	}

	/** RETURNING id SQL을 실행하고 fixture 식별자가 없으면 즉시 실패한다. */
	private long insertId(String sql, Object... arguments) {
		Long id = jdbcTemplate.queryForObject(sql, Long.class, arguments);
		if (id == null) {
			throw new IllegalStateException("test fixture insert returned no id");
		}
		return id;
	}

	/** PostgreSQL JDBC가 명확히 처리할 수 있는 서울 offset 시각으로 바꾼다. */
	private static OffsetDateTime atSeoul(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneId.of("Asia/Seoul"));
	}

	/** FK 자식부터 삭제해 각 테스트의 데이터 격리를 보장한다. */
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
	 * 각 테스트가 직접 제어할 수 있는 업무 시계를 등록한다.
	 */
	@TestConfiguration
	static class TestClockConfiguration {

		/** 운영 Clock 대신 요청 사이에 전진시킬 수 있는 시험 시계를 우선 주입한다. */
		@Bean
		@Primary
		MutableTestClock mutableAttendanceClock() {
			return new MutableTestClock(RECEIVED_AT, ZoneId.of("Asia/Seoul"));
		}
	}

	/**
	 * 같은 Spring context 안에서 요청별 수신 시각을 바꿀 수 있는 thread-safe 시험 시계다.
	 */
	static final class MutableTestClock extends Clock {

		private final AtomicReference<Instant> currentInstant;
		private final ZoneId zone;

		/** 최초 시각과 업무 시간대를 가진 시계를 만든다. */
		MutableTestClock(Instant initialInstant, ZoneId zone) {
			this(new AtomicReference<>(initialInstant), zone);
		}

		/** 다른 시간대 view도 같은 현재 시각 저장소를 공유한다. */
		private MutableTestClock(
				AtomicReference<Instant> currentInstant,
				ZoneId zone) {
			this.currentInstant = currentInstant;
			this.zone = zone;
		}

		/** 다음 HTTP 요청이 사용할 현재 시각을 바꾼다. */
		void setInstant(Instant instant) {
			currentInstant.set(instant);
		}

		/** 이 시계가 날짜와 offset을 계산할 기준 시간대를 반환한다. */
		@Override
		public ZoneId getZone() {
			return zone;
		}

		/** 현재 시각 저장소는 공유하면서 요청한 시간대의 시계를 반환한다. */
		@Override
		public Clock withZone(ZoneId requestedZone) {
			if (zone.equals(requestedZone)) {
				return this;
			}
			return new MutableTestClock(currentInstant, requestedZone);
		}

		/** 서버가 현재 요청 수신 시각으로 사용할 값을 반환한다. */
		@Override
		public Instant instant() {
			return currentInstant.get();
		}
	}
}
