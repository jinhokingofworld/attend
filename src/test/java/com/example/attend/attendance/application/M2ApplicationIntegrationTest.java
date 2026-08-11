package com.example.attend.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.attend.access.api.AccountActor;
import com.example.attend.attendance.domain.AttendanceParentStatus;
import com.example.attend.attendance.domain.AttendanceStatus;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.common.error.DepartmentAccessDeniedException;
import com.example.attend.common.error.ResourceNotFoundException;
import com.example.attend.organization.application.AddTeacherCommand;
import com.example.attend.organization.application.TeacherRegistrationResult;
import com.example.attend.organization.application.TeacherRosterService;
import com.example.attend.organization.application.UpdateTeacherCommand;
import com.example.attend.organization.domain.CardDisposition;
import com.example.attend.organization.domain.NfcUid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * M2의 대표 업무 흐름을 실제 PostgreSQL과 Spring transaction으로 검증한다.
 *
 * <p>사용자의 최소 테스트 요청에 맞춰 화면·장치 계층을 만들지 않고, 한 시나리오에서
 * 교사·카드, 정책 발행, 날짜 snapshot, 수동 출석, 자동 결석, 통계와 부서 제외의
 * 핵심 원자성을 확인한다.</p>
 */
@SpringBootTest(properties = "attendance.admin.write-enabled=true")
@ActiveProfiles("test")
@Testcontainers
@Import(M2ApplicationIntegrationTest.FixedClockConfiguration.class)
class M2ApplicationIntegrationTest {

	private static final ZoneId ATTENDANCE_ZONE = ZoneId.of("Asia/Seoul");

	/** 테스트 클래스가 공유하는 실제 PostgreSQL 15 서버다. */
	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:15-alpine");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TeacherRosterService teacherRosterService;

	@Autowired
	private AttendancePolicyService policyService;

	@Autowired
	private AttendanceDayService dayService;

	@Autowired
	private AttendanceCorrectionService correctionService;

	@Autowired
	private AttendanceTargetService targetService;

	@Autowired
	private FinalizeAttendanceDayService finalizationService;

	@Autowired
	private AttendanceStatisticsService statisticsService;

	@Autowired
	private DepartmentMembershipExclusionService exclusionService;

	@Autowired
	private MutableClock clock;

	@Autowired
	private ApplicationContext applicationContext;

	/** 생성과 정책 교체는 마지막 포함 상한의 정확히 1µs 뒤를 마감 경계로 고정한다. */
	@Test
	void snapshotsTheFirstMicrosecondAfterTheFinalPolicyBand() {
		LocalDate attendanceDate = LocalDate.of(2026, 8, 20);
		clock.setInstant(atSeoul(LocalDate.of(2026, 8, 1), LocalTime.of(8, 0)));
		TestAuthority authority = createAuthority();
		AccountActor actor = new AccountActor(authority.accountId());
		long firstPolicyId = createPublishedPolicy(actor, authority.departmentId());
		long dayId = dayService.createDay(
				actor,
				authority.departmentId(),
				attendanceDate,
				firstPolicyId);

		assertThat(finalizationDueAtInSeoul(dayId))
				.isEqualTo("2026-08-20 09:15:00.000001");

		long replacementPolicyId = policyService.createDraft(
				actor,
				authority.departmentId(),
				new PolicyDraftCommand(
						"교체 마감 정책",
						LocalTime.of(8, 30),
						List.of(
								new PolicyBandInput(
										1,
										"정상 출석",
										AttendanceParentStatus.PRESENT,
										LocalTime.of(9, 0)),
								new PolicyBandInput(
										2,
										"1차 지각",
										AttendanceParentStatus.LATE,
										LocalTime.of(9, 30)))));
		policyService.publish(actor, authority.departmentId(), replacementPolicyId);
		dayService.changePolicy(
				actor,
				authority.departmentId(),
				dayId,
				replacementPolicyId);

		assertThat(finalizationDueAtInSeoul(dayId))
				.isEqualTo("2026-08-20 09:30:00.000001");

		clock.setInstant(atSeoul(attendanceDate, LocalTime.of(9, 30)));
		assertThat(finalizationService.findPendingDayIds()).doesNotContain(dayId);
		assertThatThrownBy(() -> finalizationService.finalizeDay(dayId))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("not ready");

		clock.setInstant(atSeoul(attendanceDate, LocalTime.of(9, 30))
				.plus(1, ChronoUnit.MICROS));
		assertThat(finalizationService.findPendingDayIds()).contains(dayId);
		assertThat(finalizationService.finalizeDay(dayId)).isZero();
		assertThat(queryString(
				"SELECT status FROM public.attendance_day WHERE id = ?", dayId))
				.isEqualTo("FINALIZED");
	}

	/** 신규·수정 command는 생일과 만 나이의 근거인 정확한 생년월일을 요구한다. */
	@Test
	void requiresExactBirthDateAndRejectsFutureBirthDate() {
		assertThatThrownBy(() -> new AddTeacherCommand(
				"생년월일 누락 교사", null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("생년월일은 필수입니다.");
		assertThatThrownBy(() -> new UpdateTeacherCommand(
				"생년월일 누락 교사", null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("생년월일은 필수입니다.");

		clock.setInstant(atSeoul(
				LocalDate.of(2026, 8, 1),
				LocalTime.of(8, 0)));
		TestAuthority authority = createAuthority();
		AccountActor actor = new AccountActor(authority.accountId());
		TeacherRegistrationResult existing = teacherRosterService.addTeacher(
				actor,
				authority.departmentId(),
				new AddTeacherCommand(
						"정상 생년월일 교사",
						null,
						LocalDate.of(1990, 1, 1),
						null));
		assertThatThrownBy(() -> teacherRosterService.addTeacher(
				actor,
				authority.departmentId(),
				new AddTeacherCommand(
						"미래 생년월일 교사",
						null,
						LocalDate.of(2026, 8, 2),
						null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("생년월일은 미래일 수 없습니다.");
		assertThatThrownBy(() -> teacherRosterService.updateTeacher(
				actor,
				authority.departmentId(),
				existing.memberId(),
				new UpdateTeacherCommand(
						"정상 생년월일 교사",
						null,
						LocalDate.of(2026, 8, 2))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("생년월일은 미래일 수 없습니다.");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT birth FROM public.member WHERE id = ?",
				LocalDate.class,
				existing.memberId()))
				.isEqualTo(LocalDate.of(1990, 1, 1));
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.member
				WHERE name = '미래 생년월일 교사'
				""")).isZero();
	}

	/**
	 * 확정된 M2 정책이 실제 DB 행과 감사 이력으로 끝까지 이어지는지 검증한다.
	 */
	@Test
	void completesTheM2RosterPolicyAttendanceAndFinalizationFlow() {
		clock.setInstant(atSeoul(
				LocalDate.of(2026, 8, 1),
				LocalTime.of(8, 0)));
		TestAuthority authority = createAuthority();
		AccountActor actor = new AccountActor(authority.accountId());

		TeacherRegistrationResult firstTeacher = teacherRosterService.addTeacher(
				actor,
				authority.departmentId(),
				new AddTeacherCommand(
						"첫 번째 교사",
						"010-0000-0001",
						LocalDate.of(1990, 3, 15),
						new NfcUid("A1B2C3D4")));
		TeacherRegistrationResult secondTeacher = teacherRosterService.addTeacher(
				actor,
				authority.departmentId(),
				new AddTeacherCommand(
						"두 번째 교사",
						null,
						LocalDate.of(1992, 5, 10),
						null));
		teacherRosterService.updateTeacher(
				actor,
				authority.departmentId(),
				firstTeacher.memberId(),
				new UpdateTeacherCommand(
						"첫 번째 교사 수정",
						"010-0000-0011",
						LocalDate.of(1991, 4, 16)));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT birth FROM public.member WHERE id = ?",
				LocalDate.class,
				firstTeacher.memberId()))
				.isEqualTo(LocalDate.of(1991, 4, 16));
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.audit_log
				WHERE department_id = ?
				  AND action IN ('TEACHER_ADDED', 'TEACHER_UPDATED')
				  AND (
				      coalesce(before_data::text, '') LIKE '%1990-03-15%'
				      OR coalesce(after_data::text, '') LIKE '%1990-03-15%'
				      OR coalesce(before_data::text, '') LIKE '%1991-04-16%'
				      OR coalesce(after_data::text, '') LIKE '%1991-04-16%'
				      OR coalesce(before_data::text, '') LIKE '%첫 번째 교사%'
				      OR coalesce(after_data::text, '') LIKE '%첫 번째 교사%'
				      OR coalesce(before_data::text, '') LIKE '%010-0000-0001%'
				      OR coalesce(after_data::text, '') LIKE '%010-0000-0001%'
				      OR coalesce(before_data::text, '') LIKE '%010-0000-0011%'
				      OR coalesce(after_data::text, '') LIKE '%010-0000-0011%'
				  )
				""", authority.departmentId())).isZero();
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.audit_log
				WHERE department_id = ?
				  AND action = 'TEACHER_UPDATED'
				  AND after_data -> 'updatedFields'
				      = '["name", "phone", "birth"]'::jsonb
				""", authority.departmentId())).isEqualTo(1);

		teacherRosterService.updateTeacher(
				actor,
				authority.departmentId(),
				firstTeacher.memberId(),
				new UpdateTeacherCommand(
						"첫 번째 교사 수정",
						"010-0000-0012",
						LocalDate.of(1991, 4, 16)));
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.audit_log
				WHERE department_id = ?
				  AND action = 'TEACHER_UPDATED'
				  AND after_data -> 'updatedFields' = '["phone"]'::jsonb
				""", authority.departmentId())).isEqualTo(1);
		teacherRosterService.updateTeacher(
				actor,
				authority.departmentId(),
				firstTeacher.memberId(),
				new UpdateTeacherCommand(
						"첫 번째 교사 수정",
						"010-0000-0012",
						LocalDate.of(1991, 4, 16)));
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.audit_log
				WHERE department_id = ?
				  AND action = 'TEACHER_UPDATED'
				""", authority.departmentId())).isEqualTo(2);

		long policyId = policyService.createDraft(
				actor,
				authority.departmentId(),
				new PolicyDraftCommand(
						"주일 기본 정책",
						LocalTime.of(8, 30),
						List.of(
								new PolicyBandInput(
										1,
										"정상 출석",
										AttendanceParentStatus.PRESENT,
										LocalTime.of(9, 0)),
								new PolicyBandInput(
										2,
										"1차 지각",
										AttendanceParentStatus.LATE,
										LocalTime.of(9, 15)))));
		policyService.replaceDraft(
				actor,
				authority.departmentId(),
				policyId,
				new PolicyDraftCommand(
						"주일 기본 정책 수정",
						LocalTime.of(8, 30),
						List.of(
								new PolicyBandInput(
										1,
										"정상 출석",
										AttendanceParentStatus.PRESENT,
										LocalTime.of(9, 0)),
								new PolicyBandInput(
										2,
										"지각 수정",
										AttendanceParentStatus.LATE,
										LocalTime.of(9, 20)))));
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.audit_log
				WHERE department_id = ?
				  AND action = 'POLICY_DRAFT_REPLACED'
				  AND before_data ->> 'name' = '주일 기본 정책'
				  AND after_data ->> 'name' = '주일 기본 정책 수정'
				  AND before_data #>> '{bands,1,label}' = '1차 지각'
				  AND after_data #>> '{bands,1,label}' = '지각 수정'
				  AND before_data #>> '{bands,1,upperTime}' = '09:15'
				  AND after_data #>> '{bands,1,upperTime}' = '09:20'
				""", authority.departmentId())).isEqualTo(1);
		policyService.publish(actor, authority.departmentId(), policyId);

		assertThatThrownBy(() -> policyService.replaceDraft(
				actor,
				authority.departmentId(),
				policyId,
						new PolicyDraftCommand(
								"발행 후 수정 시도",
								LocalTime.of(8, 0),
								List.of())))
				.isInstanceOf(ResourceNotFoundException.class);

		LocalDate attendanceDate = LocalDate.of(2026, 8, 1);
		long dayId = dayService.createDay(
				actor,
				authority.departmentId(),
				attendanceDate,
				policyId);
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.attendance_target
				WHERE attendance_day_id = ?
				  AND is_target
				""", dayId)).isEqualTo(2);
		targetService.removeTarget(
				actor,
				authority.departmentId(),
				dayId,
				secondTeacher.memberId(),
				"이번 날짜 대상 제외");
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.attendance_target
				WHERE attendance_day_id = ?
				  AND is_target
				""", dayId)).isEqualTo(1);
		targetService.addTarget(
				actor,
				authority.departmentId(),
				dayId,
				secondTeacher.memberId(),
				"대상자 재확인");

		clock.setInstant(atSeoul(attendanceDate, LocalTime.of(9, 10)));
		assertThatThrownBy(() -> dayService.createDay(
				actor,
				authority.departmentId(),
				attendanceDate,
				policyId))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("태깅 시작 시각");
		ManualAttendanceResult firstResult = correctionService.correct(
				actor,
				authority.departmentId(),
				dayId,
				firstTeacher.memberId(),
				new ManualAttendanceCommand(
						atSeoul(attendanceDate, LocalTime.of(9, 0)),
						false,
						false,
						"현장 확인",
						"장치 도입 전 수동 확인"));
		assertThat(firstResult.status()).isEqualTo(AttendanceStatus.PRESENT);

		clock.setInstant(atSeoul(attendanceDate.plusDays(1), LocalTime.of(0, 5)));
		assertThat(finalizationService.findPendingDayIds()).containsExactly(dayId);
		assertThat(finalizationService.finalizeDay(dayId)).isEqualTo(1);
		assertThat(finalizationService.finalizeDay(dayId)).isZero();
		correctionService.updateNote(
				actor,
				authority.departmentId(),
				dayId,
				secondTeacher.memberId(),
				"자동 결석 확인",
				"메모만 추가");
		assertThat(queryString("""
				SELECT source
				FROM public.attendance_record
				WHERE attendance_day_id = ?
				  AND member_id = ?
				""", dayId, secondTeacher.memberId())).isEqualTo("AUTO_ABSENCE");

		AttendanceStatistics firstStatistics =
				statisticsService.getMemberStatistics(
						actor,
						authority.departmentId(),
						firstTeacher.memberId(),
						attendanceDate,
						attendanceDate);
		AttendanceStatistics secondStatistics =
				statisticsService.getMemberStatistics(
						actor,
						authority.departmentId(),
						secondTeacher.memberId(),
						attendanceDate,
						attendanceDate);
		assertThat(firstStatistics.totalCount()).isEqualTo(1);
		assertThat(firstStatistics.presentCount()).isEqualTo(1);
		assertThat(firstStatistics.presentRate()).isEqualTo(100.0);
		assertThat(secondStatistics.absentCount()).isEqualTo(1);
		assertThat(secondStatistics.absentRate()).isEqualTo(100.0);

		exclusionService.exclude(
				actor,
				authority.departmentId(),
				firstTeacher.memberId(),
				new ExcludeTeacherCommand(
						Set.of(),
						CardDisposition.LOST,
						"부서 사역 종료"));

		assertThat(queryString("""
				SELECT status
				FROM public.nfc_card
				WHERE id = ?
				""", firstTeacher.cardId())).isEqualTo("LOST");
		assertThat(queryBoolean("""
				SELECT active
				FROM public.member
				WHERE id = ?
				""", firstTeacher.memberId())).isFalse();
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.attendance_record
				WHERE attendance_day_id = ?
				""", dayId)).isEqualTo(2);
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.audit_log
				WHERE department_id = ?
				""", authority.departmentId())).isGreaterThanOrEqualTo(10);
		assertThat(applicationContext.containsBean("attendanceFinalizationScheduler"))
				.isFalse();
	}

	/**
	 * 부서 제외는 확정된 과거·시작 후·기록 있는 날짜를 보존하고,
	 * 확인한 시작 전·기록 없는 대상 집합만 자동 제외하는지 검증한다.
	 */
	@Test
	void excludesOnlyUnstartedRecordlessTargetsAndKeepsThemExcludedAfterRejoin() {
		clock.setInstant(atSeoul(
				LocalDate.of(2026, 8, 1),
				LocalTime.of(8, 0)));
		TestAuthority authority = createAuthority();
		AccountActor actor = new AccountActor(authority.accountId());
		TeacherRegistrationResult teacher = teacherRosterService.addTeacher(
				actor,
				authority.departmentId(),
				new AddTeacherCommand(
						"제외 정책 교사",
						null,
						LocalDate.of(1993, 6, 11),
						null));
		TeacherRegistrationResult recordedTeacher = teacherRosterService.addTeacher(
				actor,
				authority.departmentId(),
				new AddTeacherCommand(
						"기록 보존 교사",
						null,
						LocalDate.of(1994, 7, 12),
						null));
		long policyId = createPublishedPolicy(actor, authority.departmentId());

		long pastDayId = dayService.createDay(
				actor,
				authority.departmentId(),
				LocalDate.of(2026, 8, 2),
				policyId);
		long startedDayId = dayService.createDay(
				actor,
				authority.departmentId(),
				LocalDate.of(2026, 8, 3),
				policyId);
		long recordedFutureDayId = dayService.createDay(
				actor,
				authority.departmentId(),
				LocalDate.of(2026, 8, 4),
				policyId);
		long eligibleFutureDayId = dayService.createDay(
				actor,
				authority.departmentId(),
				LocalDate.of(2026, 8, 5),
				policyId);
		jdbcTemplate.update("""
				INSERT INTO public.attendance_record(
				    attendance_day_id, policy_version_id, member_id,
				    status, source, created_by_account_id)
				VALUES (?, ?, ?, 'ABSENT', 'MANUAL', ?)
				""",
				recordedFutureDayId,
				policyId,
				recordedTeacher.memberId(),
				authority.accountId());

		clock.setInstant(atSeoul(
				LocalDate.of(2026, 8, 3),
				LocalTime.of(9, 0)));
		assertThatThrownBy(() -> exclusionService.exclude(
				actor,
				authority.departmentId(),
				teacher.memberId(),
				new ExcludeTeacherCommand(
						Set.of(pastDayId),
						CardDisposition.AVAILABLE,
						"부서 이동")))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("현재 영향을 다시 확인");
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.department_membership
				WHERE id = ?
				  AND ended_at IS NULL
				""", teacher.membershipId())).isEqualTo(1);

		exclusionService.exclude(
				actor,
				authority.departmentId(),
				teacher.memberId(),
				new ExcludeTeacherCommand(
						Set.of(eligibleFutureDayId),
						CardDisposition.AVAILABLE,
						"부서 이동"));

		assertThat(queryBoolean("""
				SELECT is_target
				FROM public.attendance_target
				WHERE attendance_day_id = ? AND member_id = ?
				""", pastDayId, teacher.memberId())).isTrue();
		assertThat(queryBoolean("""
				SELECT is_target
				FROM public.attendance_target
				WHERE attendance_day_id = ? AND member_id = ?
				""", startedDayId, teacher.memberId())).isTrue();
		assertThat(queryBoolean("""
				SELECT is_target
				FROM public.attendance_target
				WHERE attendance_day_id = ? AND member_id = ?
				""", recordedFutureDayId, teacher.memberId())).isTrue();
		assertThat(queryBoolean("""
				SELECT is_target
				FROM public.attendance_target
				WHERE attendance_day_id = ? AND member_id = ?
				""", eligibleFutureDayId, teacher.memberId())).isFalse();

		jdbcTemplate.update(
				"UPDATE public.member SET active = TRUE WHERE id = ?",
				teacher.memberId());
		jdbcTemplate.update("""
				INSERT INTO public.department_membership(
				    department_id, member_id, joined_at, created_by_account_id)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?)
				""",
				authority.departmentId(),
				teacher.memberId(),
				authority.accountId());
		assertThat(queryBoolean("""
				SELECT is_target
				FROM public.attendance_target
				WHERE attendance_day_id = ? AND member_id = ?
				""", eligibleFutureDayId, teacher.memberId())).isFalse();
	}

	/**
	 * 같은 날짜를 두 worker가 동시에 마감해도 결석·상태·감사가 한 번만 생기는지 검증한다.
	 */
	@Test
	void finalizesOneAttendanceDayOnlyOnceUnderConcurrency()
			throws ExecutionException, InterruptedException {
		LocalDate attendanceDate = LocalDate.of(2026, 8, 10);
		clock.setInstant(atSeoul(attendanceDate, LocalTime.of(8, 0)));
		TestAuthority authority = createAuthority();
		AccountActor actor = new AccountActor(authority.accountId());
		TeacherRegistrationResult teacher = teacherRosterService.addTeacher(
				actor,
				authority.departmentId(),
				new AddTeacherCommand(
						"동시 마감 교사",
						null,
						LocalDate.of(1995, 8, 13),
						null));
		long policyId = createPublishedPolicy(actor, authority.departmentId());
		long dayId = dayService.createDay(
				actor,
				authority.departmentId(),
				attendanceDate,
				policyId);
		clock.setInstant(atSeoul(attendanceDate.plusDays(1), LocalTime.of(0, 5)));

		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<Integer> first = executor.submit(
					() -> finalizeAfterSignal(start, dayId));
			Future<Integer> second = executor.submit(
					() -> finalizeAfterSignal(start, dayId));
			start.countDown();

			assertThat(List.of(first.get(), second.get()))
					.containsExactlyInAnyOrder(0, 1);
		}

		assertThat(queryInt("""
				SELECT count(*)
				FROM public.attendance_record
				WHERE attendance_day_id = ?
				  AND member_id = ?
				  AND status = 'ABSENT'
				""", dayId, teacher.memberId())).isEqualTo(1);
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.audit_log
				WHERE idempotency_key = ?
				""", "attendance-day:" + dayId + ":finalize")).isEqualTo(1);
		assertThat(queryString("""
				SELECT status
				FROM public.attendance_day
				WHERE id = ?
				""", dayId)).isEqualTo("FINALIZED");
	}

	/**
	 * 사용자 입력 날짜가 잘못되어도 부서 권한 검사를 우회하지 않는지 검증한다.
	 */
	@Test
	void authorizesDepartmentBeforeValidatingAttendanceDate() {
		clock.setInstant(atSeoul(
				LocalDate.of(2026, 8, 1),
				LocalTime.of(8, 0)));
		TestAuthority targetDepartment = createAuthority();
		TestAuthority unauthorizedAccount = createAuthority();

		assertThatThrownBy(() -> dayService.createDay(
				new AccountActor(unauthorizedAccount.accountId()),
				targetDepartment.departmentId(),
				LocalDate.of(2026, 7, 31),
				1L))
				.isInstanceOf(DepartmentAccessDeniedException.class);
	}

	@Test
	void createsRecurringDaysSnapshotsTargetsAndSkipsExistingDates() {
		clock.setInstant(atSeoul(
				LocalDate.of(2026, 8, 1),
				LocalTime.of(8, 0)));
		TestAuthority authority = createAuthority();
		AccountActor actor = new AccountActor(authority.accountId());
		TeacherRegistrationResult teacher = teacherRosterService.addTeacher(
				actor,
				authority.departmentId(),
				new AddTeacherCommand(
						"반복 생성 교사",
						null,
						LocalDate.of(1992, 3, 4),
						null));
		long policyId = createPublishedPolicy(actor, authority.departmentId());
		AttendanceDayScheduleCommand command = new AttendanceDayScheduleCommand(
				LocalDate.of(2026, 8, 2),
				LocalDate.of(2026, 8, 6),
				policyId,
				AttendanceDayRecurrence.DAILY,
				2,
				Set.of(), Set.of(), null, null);

		AttendanceDayBatchResult first = dayService.createDays(
				actor, authority.departmentId(), command);
		AttendanceDayBatchResult second = dayService.createDays(
				actor, authority.departmentId(), command);

		assertThat(first).isEqualTo(new AttendanceDayBatchResult(3, 0));
		assertThat(second).isEqualTo(new AttendanceDayBatchResult(0, 3));
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.attendance_day
				WHERE department_id = ?
				  AND attendance_date BETWEEN ? AND ?
				""", authority.departmentId(),
				LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 6))).isEqualTo(3);
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.attendance_target AS target
				JOIN public.attendance_day AS day
				  ON day.id = target.attendance_day_id
				WHERE day.department_id = ?
				  AND target.member_id = ?
				""", authority.departmentId(), teacher.memberId())).isEqualTo(3);
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.audit_log
				WHERE department_id = ?
				  AND action = 'ATTENDANCE_DAY_CREATED'
				""", authority.departmentId())).isEqualTo(3);
	}

	@Test
	void rejectsAPastScheduleStartEvenWhenEveryOccurrenceIsInTheFuture() {
		clock.setInstant(atSeoul(
				LocalDate.of(2026, 8, 6),
				LocalTime.of(8, 0)));
		TestAuthority authority = createAuthority();
		AccountActor actor = new AccountActor(authority.accountId());
		long policyId = createPublishedPolicy(actor, authority.departmentId());
		AttendanceDayScheduleCommand command = new AttendanceDayScheduleCommand(
				LocalDate.of(2026, 8, 5),
				LocalDate.of(2026, 8, 7),
				policyId,
				AttendanceDayRecurrence.WEEKLY,
				1,
				Set.of(DayOfWeek.FRIDAY),
				Set.of(), null, null);

		assertThat(command.occurrenceDates())
				.containsExactly(LocalDate.of(2026, 8, 7));
		assertThatThrownBy(() -> dayService.createDays(
				actor, authority.departmentId(), command))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessage("과거 출석 날짜는 생성할 수 없습니다.");
		assertThat(queryInt("""
				SELECT count(*)
				FROM public.attendance_day
				WHERE department_id = ?
				""", authority.departmentId())).isZero();
	}

	/**
	 * 동시성 테스트 worker가 같은 신호 이후 날짜별 마감 transaction을 호출한다.
	 */
	private int finalizeAfterSignal(CountDownLatch start, long dayId)
			throws InterruptedException {
		start.await();
		return finalizationService.finalizeDay(dayId);
	}

	/**
	 * 동시성 fixture에서 사용할 동일한 2구간 정책을 만들고 발행한다.
	 */
	private long createPublishedPolicy(AccountActor actor, long departmentId) {
		long policyId = policyService.createDraft(
				actor,
				departmentId,
				new PolicyDraftCommand(
						"동시 마감 정책",
						LocalTime.of(8, 30),
						List.of(
								new PolicyBandInput(
										1,
										"정상 출석",
										AttendanceParentStatus.PRESENT,
										LocalTime.of(9, 0)),
								new PolicyBandInput(
										2,
										"1차 지각",
										AttendanceParentStatus.LATE,
										LocalTime.of(9, 15)))));
		policyService.publish(actor, departmentId, policyId);
		return policyId;
	}

	/**
	 * 테스트에서 사용할 활성 관리자 계정, 부서와 역할을 직접 준비한다.
	 */
	private TestAuthority createAuthority() {
		String fixtureSuffix = UUID.randomUUID().toString().substring(0, 8);
		long accountId = jdbcTemplate.queryForObject("""
				INSERT INTO public.account (
					username,
					password_hash,
					status,
					password_changed_at
				)
				VALUES (
					?,
					'test-only-hash',
					'ACTIVE',
					CURRENT_TIMESTAMP
				)
				RETURNING id
				""", Long.class, "m2-admin-" + fixtureSuffix);
		long departmentId = jdbcTemplate.queryForObject("""
				INSERT INTO public.department (name)
				VALUES (?)
				RETURNING id
				""", Long.class, "M2 통합 테스트 부서 " + fixtureSuffix);
		jdbcTemplate.update("""
				INSERT INTO public.account_department_role (
					account_id,
					department_id,
					role,
					assigned_by_account_id
				)
				VALUES (?, ?, 'DEPARTMENT_ADMIN', ?)
				""", accountId, departmentId, accountId);
		return new TestAuthority(accountId, departmentId);
	}

	/** Asia/Seoul 현지 날짜·시각을 서버 Instant로 바꾼다. */
	private static Instant atSeoul(LocalDate date, LocalTime time) {
		return date.atTime(time).atZone(ATTENDANCE_ZONE).toInstant();
	}

	/** 단일 정수 SQL 결과를 읽는다. */
	private int queryInt(String sql, Object... arguments) {
		return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	/** 단일 문자열 SQL 결과를 읽는다. */
	private String queryString(String sql, Object... arguments) {
		return jdbcTemplate.queryForObject(sql, String.class, arguments);
	}

	/** 단일 boolean SQL 결과를 읽는다. */
	private boolean queryBoolean(String sql, Object... arguments) {
		return jdbcTemplate.queryForObject(sql, Boolean.class, arguments);
	}

	/** 저장된 마감 시각을 서울 현지 마이크로초 문자열로 읽는다. */
	private String finalizationDueAtInSeoul(long dayId) {
		return queryString("""
				SELECT to_char(
				    finalization_due_at AT TIME ZONE 'Asia/Seoul',
				    'YYYY-MM-DD HH24:MI:SS.US')
				FROM public.attendance_day
				WHERE id = ?
				""", dayId);
	}

	/**
	 * 테스트가 생성한 권한 fixture의 식별자다.
	 */
	private record TestAuthority(long accountId, long departmentId) {
	}

	/**
	 * 실제 서비스에 주입할 가변 테스트 Clock을 우선 빈으로 제공한다.
	 */
	@TestConfiguration
	static class FixedClockConfiguration {

		/**
		 * 테스트 시나리오가 날짜 경과를 직접 제어할 수 있는 Clock을 만든다.
		 *
		 * @return Asia/Seoul 기반 가변 Clock
		 */
		@Bean
		@Primary
		MutableClock mutableAttendanceClock() {
			return new MutableClock(
					atSeoul(LocalDate.of(2026, 8, 1), LocalTime.of(8, 0)),
					ATTENDANCE_ZONE);
		}
	}

	/**
	 * 호출 중 시간대는 유지하고 현재 Instant만 전진시킬 수 있는 테스트 Clock이다.
	 */
	static final class MutableClock extends Clock {

		private volatile Instant instant;
		private final ZoneId zone;

		/**
		 * 시작 시각과 시간대를 고정해 Clock을 만든다.
		 */
		MutableClock(Instant instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		/** 테스트 업무 시각을 명시적으로 변경한다. */
		void setInstant(Instant instant) {
			this.instant = instant;
		}

		/** {@inheritDoc} */
		@Override
		public ZoneId getZone() {
			return zone;
		}

		/** {@inheritDoc} */
		@Override
		public Clock withZone(ZoneId newZone) {
			return new MutableClock(instant, newZone);
		}

		/** {@inheritDoc} */
		@Override
		public Instant instant() {
			return instant;
		}
	}
}
