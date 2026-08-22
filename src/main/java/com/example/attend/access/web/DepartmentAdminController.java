package com.example.attend.access.web;

import com.example.attend.access.application.AdminWriteGate;
import com.example.attend.access.application.DepartmentAdminQueryService;
import com.example.attend.access.application.DepartmentAdminInvitationService;
import com.example.attend.access.security.AccountPrincipal;
import com.example.attend.attendance.application.AttendanceCorrectionService;
import com.example.attend.attendance.application.AttendanceDayBatchResult;
import com.example.attend.attendance.application.AttendanceDayRecurrence;
import com.example.attend.attendance.application.AttendanceDayScheduleCommand;
import com.example.attend.attendance.application.AttendanceDayService;
import com.example.attend.attendance.application.AttendancePolicyService;
import com.example.attend.attendance.application.AttendancePolicyScheduleService;
import com.example.attend.attendance.application.AttendanceStatistics;
import com.example.attend.attendance.application.AttendanceStatisticsService;
import com.example.attend.attendance.application.AttendanceTargetService;
import com.example.attend.attendance.application.DepartmentMembershipExclusionService;
import com.example.attend.attendance.application.ExcludeTeacherCommand;
import com.example.attend.attendance.application.ManualAttendanceCommand;
import com.example.attend.attendance.application.PolicyBandInput;
import com.example.attend.attendance.application.PolicyDraftCommand;
import com.example.attend.attendance.domain.AttendanceParentStatus;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.organization.application.AddTeacherCommand;
import com.example.attend.organization.application.CardManagementService;
import com.example.attend.organization.application.TeacherRosterService;
import com.example.attend.organization.application.UpdateTeacherCommand;
import com.example.attend.organization.domain.CardDisposition;
import com.example.attend.organization.domain.NfcUid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 기존 M2 조직·출석 서비스를 부서 범위 MVC command와 화면에 연결한다.
 */
@Controller
public final class DepartmentAdminController {

	private final DepartmentAdminQueryService queryService;
	private final DepartmentAdminInvitationService invitationService;
	private final AdminWriteGate writeGate;
	private final TeacherRosterService teacherService;
	private final CardManagementService cardService;
	private final DepartmentMembershipExclusionService exclusionService;
	private final AttendancePolicyService policyService;
	private final AttendancePolicyScheduleService policyScheduleService;
	private final AttendanceDayService dayService;
	private final AttendanceTargetService targetService;
	private final AttendanceCorrectionService correctionService;
	private final AttendanceStatisticsService statisticsService;
	private final ZoneId attendanceZone;
	private final Clock clock;
	private final boolean showTagLogs;

	/**
	 * 부서 화면에서 사용하는 M2 application service와 읽기 모델을 주입받는다.
	 */
	public DepartmentAdminController(
			DepartmentAdminQueryService queryService,
			DepartmentAdminInvitationService invitationService,
			AdminWriteGate writeGate,
			TeacherRosterService teacherService,
			CardManagementService cardService,
			DepartmentMembershipExclusionService exclusionService,
			AttendancePolicyService policyService,
			AttendancePolicyScheduleService policyScheduleService,
			AttendanceDayService dayService,
			AttendanceTargetService targetService,
			AttendanceCorrectionService correctionService,
			AttendanceStatisticsService statisticsService,
			ZoneId attendanceZone,
			Clock clock,
			@Value("${attendance.admin.show-tag-logs:false}")
			boolean showTagLogs) {
		this.queryService = queryService;
		this.invitationService = invitationService;
		this.writeGate = writeGate;
		this.teacherService = teacherService;
		this.cardService = cardService;
		this.exclusionService = exclusionService;
		this.policyService = policyService;
		this.policyScheduleService = policyScheduleService;
		this.dayService = dayService;
		this.targetService = targetService;
		this.correctionService = correctionService;
		this.statisticsService = statisticsService;
		this.attendanceZone = attendanceZone;
		this.clock = clock;
		this.showTagLogs = showTagLogs;
	}

	/** 현재 부서의 관리자만 동료 부서 관리자를 초대할 수 있다. */
	@PostMapping("/admin/departments/{departmentId}/admin-invitations")
	public String inviteAdministrator(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@RequestParam String email,
			RedirectAttributes redirect) {
		try {
			invitationService.invite(principal.toActor(), departmentId, email, false);
			redirect.addFlashAttribute("message", "관리자 초대 메일 발송을 예약했습니다.");
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		}
		return "redirect:/admin/departments/" + departmentId;
	}

	/** 현재 부서의 실패한 관리자 초대 메일만 다시 전송할 수 있다. */
	@PostMapping("/admin/departments/{departmentId}/admin-invitations/{outboxId}/resend")
	public String resendAdministratorInvitation(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long outboxId,
			RedirectAttributes redirect) {
		try {
			invitationService.resend(principal.toActor(), departmentId, outboxId, false);
			redirect.addFlashAttribute("message", "관리자 초대 메일 재전송을 예약했습니다.");
		} catch (BusinessRuleException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
		}
		return "redirect:/admin/departments/" + departmentId;
	}

	/** 오늘의 출석 집계와 부서 내비게이션을 표시한다. */
	@GetMapping("/admin/departments/{departmentId}")
	public String dashboard(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		Map<String, Object> dashboard =
				queryService.dashboard(principal.toActor(), departmentId);
		model.addAttribute("dashboard", dashboard);
		model.addAttribute("dashboardRows",
				dashboardRows(principal, departmentId, dashboard));
		model.addAttribute("invitations",
				invitationService.invitations(principal.toActor(), departmentId));
		return "admin/department/dashboard";
	}

	/**
	 * 새로고침 없이 오늘의 출석 현황을 갱신할 수 있는 부서 범위 JSON을 반환한다.
	 *
	 * <p>개인 연락처와 NFC UID는 포함하지 않고 대시보드에 필요한 교사 이름과
	 * 출석 판정만 전달한다.</p>
	 *
	 * @param principal 인증 계정
	 * @param departmentId 부서 식별자
	 * @return 오늘 집계와 대상 교사 행
	 */
	@GetMapping("/admin/departments/{departmentId}/dashboard-data")
	@ResponseBody
	public Map<String, Object> dashboardData(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId) {
		Map<String, Object> dashboard =
				queryService.dashboard(principal.toActor(), departmentId);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("summary", dashboard);
		response.put("rows", dashboardRows(
				principal, departmentId, dashboard));
		return response;
	}

	/** 활성 교사와 카드 상태를 표시한다. */
	@GetMapping("/admin/departments/{departmentId}/teachers")
	public String teachers(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		model.addAttribute("teachers",
				queryService.teachers(principal.toActor(), departmentId));
		return "admin/department/teachers";
	}

	/**
	 * 교사 기본정보, 기간별 공식 통계와 최근 출석 이력을 표시한다.
	 *
	 * @param principal 인증 계정
	 * @param departmentId 부서 식별자
	 * @param memberId 교사 식별자
	 * @param fromDate 선택 통계 시작일
	 * @param toDate 선택 통계 종료일
	 * @param model 화면 모델
	 * @return 교사 상세 템플릿
	 */
	@GetMapping("/admin/departments/{departmentId}/teachers/{memberId}")
	public String teacher(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long memberId,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
			@RequestParam(defaultValue = "false") boolean edit,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		LocalDate effectiveTo = toDate == null ? LocalDate.now(clock) : toDate;
		LocalDate effectiveFrom = fromDate == null
				? effectiveTo.minusYears(1).plusDays(1)
				: fromDate;
		model.addAttribute("teacher", queryService.teacher(
				principal.toActor(), departmentId, memberId));
		AttendanceStatistics statistics;
		boolean statisticsRangeValid = !effectiveFrom.isAfter(effectiveTo);
		if (!statisticsRangeValid) {
			model.addAttribute("error",
					"시작일은 종료일보다 늦을 수 없습니다.");
			statistics = AttendanceStatistics.empty();
		} else {
			statistics = statisticsService.getMemberStatistics(
					principal.toActor(), departmentId, memberId,
					effectiveFrom, effectiveTo);
		}
		model.addAttribute("statistics", statistics);
		model.addAttribute("statisticsRangeValid", statisticsRangeValid);
		model.addAttribute("attendanceHistory",
				queryService.teacherAttendanceHistory(
						principal.toActor(), departmentId, memberId));
		model.addAttribute("fromDate", effectiveFrom);
		model.addAttribute("toDate", effectiveTo);
		model.addAttribute("editMode", edit && writeGate.isEnabled());
		return "admin/department/teacher-detail";
	}

	/** 교사·활성 소속과 선택 카드를 한 트랜잭션으로 추가한다. */
	@PostMapping("/admin/departments/{departmentId}/teachers")
	public String addTeacher(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@RequestParam String name,
			@RequestParam(required = false) String phone,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birth,
			@RequestParam(required = false) String cardUid,
			RedirectAttributes redirect) {
		return command(
				() -> teacherService.addTeacher(
						principal.toActor(),
						departmentId,
						new AddTeacherCommand(
								name,
								phone,
								birth,
								optionalUid(cardUid))),
				"교사를 추가했습니다.",
				teachersPath(departmentId),
				redirect);
	}

	/** 부서 범위가 확인된 교사의 허용 필드만 수정한다. */
	@PostMapping("/admin/departments/{departmentId}/teachers/{memberId}/update")
	public String updateTeacher(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long memberId,
			@RequestParam String name,
			@RequestParam(required = false) String phone,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birth,
			RedirectAttributes redirect) {
		return command(
				() -> teacherService.updateTeacher(
						principal.toActor(),
						departmentId,
						memberId,
						new UpdateTeacherCommand(name, phone, birth)),
				"교사 정보를 수정했습니다.",
				teacherPath(departmentId, memberId),
				redirect);
	}

	/** 부서 제외가 미치는 현재 소속·카드·미래 대상 날짜를 확인한다. */
	@GetMapping("/admin/departments/{departmentId}/teachers/{memberId}/exclude")
	public String excludeTeacherConfirmation(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long memberId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		model.addAttribute("teacher", queryService.teacher(
				principal.toActor(), departmentId, memberId));
		List<Map<String, Object>> futureAttendanceDays =
				queryService.futureAttendanceTargets(
						principal.toActor(), departmentId, memberId);
		model.addAttribute("futureAttendanceDays", futureAttendanceDays);
		model.addAttribute(
				"futureAttendanceDayCount", futureAttendanceDays.size());
		return "admin/department/teacher-exclude";
	}

	/** 교사 소속·카드와 확인한 미래 대상자 전체를 원자적으로 종료한다. */
	@PostMapping("/admin/departments/{departmentId}/teachers/{memberId}/exclude")
	public String excludeTeacher(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long memberId,
			@RequestParam(
					name = "expectedFutureAttendanceDayIds",
					required = false
			) Set<Long> expectedFutureAttendanceDayIds,
			@RequestParam CardDisposition cardDisposition,
			@RequestParam String reason,
			@RequestParam(defaultValue = "false") boolean confirmImpact,
			RedirectAttributes redirect) {
		return command(
					() -> {
						if (!confirmImpact) {
							throw new IllegalArgumentException(
									"부서 제외 영향을 확인해야 합니다.");
						}
						exclusionService.exclude(
								principal.toActor(),
								departmentId,
								memberId,
								new ExcludeTeacherCommand(
										expectedFutureAttendanceDayIds == null
												? Set.of()
												: expectedFutureAttendanceDayIds,
										cardDisposition,
										reason));
					},
					"교사를 부서에서 제외했습니다.",
					teachersPath(departmentId),
					teacherExcludePath(departmentId, memberId),
					redirect);
	}

	/** 활성 소속 교사에게 사용 가능한 NFC 카드를 연결한다. */
	@PostMapping("/admin/departments/{departmentId}/teachers/{memberId}/card/connect")
	public String connectCard(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long memberId,
			@RequestParam String cardUid,
			RedirectAttributes redirect) {
		return command(
				() -> cardService.connect(
						principal.toActor(),
						departmentId,
						memberId,
						new NfcUid(cardUid)),
				"NFC 카드를 연결했습니다.",
				teacherPath(departmentId, memberId),
				redirect);
	}

	/** 기존 카드 종료와 새 카드 연결을 원자 처리한다. */
	@PostMapping("/admin/departments/{departmentId}/teachers/{memberId}/card/replace")
	public String replaceCard(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long memberId,
			@RequestParam String cardUid,
			@RequestParam String reason,
			RedirectAttributes redirect) {
		return command(
				() -> cardService.replace(
						principal.toActor(),
						departmentId,
						memberId,
						new NfcUid(cardUid),
						reason),
				"NFC 카드를 교체했습니다.",
				teacherPath(departmentId, memberId),
				redirect);
	}

	/** 카드 연결을 종료하고 선택한 카드 상태를 적용한다. */
	@PostMapping("/admin/departments/{departmentId}/teachers/{memberId}/card/disconnect")
	public String disconnectCard(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long memberId,
			@RequestParam CardDisposition disposition,
			@RequestParam String reason,
			RedirectAttributes redirect) {
		return command(
				() -> cardService.disconnect(
						principal.toActor(),
						departmentId,
						memberId,
						disposition,
						reason),
				"NFC 카드 연결을 종료했습니다.",
				teacherPath(departmentId, memberId),
				redirect);
	}

	/** 부서 장치에서 수신한 미등록 카드 이벤트를 표시한다. */
	@GetMapping("/admin/departments/{departmentId}/cards/inbox")
	public String cardInbox(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		model.addAttribute("events",
				queryService.cardInbox(principal.toActor(), departmentId));
		model.addAttribute("teachers",
				queryService.teachers(principal.toActor(), departmentId));
		return "admin/department/card-inbox";
	}

	/**
	 * 카드 등록함 이벤트를 선택한 활성 교사에게 원본 UID 노출 없이 연결한다.
	 *
	 * @param principal 인증 계정
	 * @param departmentId 부서 식별자
	 * @param eventId 태깅 이벤트 식별자
	 * @param memberId 연결할 교사 식별자
	 * @param redirect 처리 결과 메시지 저장소
	 * @return 카드 등록함 리다이렉트
	 */
	@PostMapping("/admin/departments/{departmentId}/cards/inbox/{eventId}/connect")
	public String connectInboxCard(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long eventId,
			@RequestParam long memberId,
			RedirectAttributes redirect) {
		return command(
				() -> cardService.connectFromInbox(
						principal.toActor(), departmentId, memberId, eventId),
				"태깅한 NFC 카드를 교사에게 연결했습니다.",
				"/admin/departments/" + departmentId + "/cards/inbox",
				redirect);
	}

	/** 부서의 정책 버전 목록과 초안 생성 form을 표시한다. */
	@GetMapping("/admin/departments/{departmentId}/policies")
	public String policies(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		model.addAttribute("policies",
				queryService.policySchedules(principal.toActor(), departmentId));
		return "admin/department/policies";
	}

	/** 한 정책의 단계와 초안 편집 form을 표시한다. */
	@GetMapping("/admin/departments/{departmentId}/policies/{policyId}")
	public String policy(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long policyId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		model.addAttribute("policy", queryService.policy(
				principal.toActor(), departmentId, policyId));
		model.addAttribute("bands", queryService.policyBands(
				principal.toActor(), departmentId, policyId));
		return "admin/department/policy-detail";
	}

	/** 동적 단계 입력으로 정책 초안을 만든다. */
	@PostMapping("/admin/departments/{departmentId}/policies")
	public String createPolicy(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@RequestParam String name,
			@RequestParam @DateTimeFormat(pattern = "HH:mm")
					LocalTime checkInStartTime,
			@RequestParam(required = false) List<String> bandLabel,
			@RequestParam(required = false) List<AttendanceParentStatus> bandStatus,
			@RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm")
					List<LocalTime> bandUpperTime,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
					LocalDate startDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
					LocalDate endDate,
			@RequestParam(required = false) AttendanceDayRecurrence recurrence,
			@RequestParam(defaultValue = "1") int interval,
			@RequestParam(required = false) List<DayOfWeek> weeklyDays,
			@RequestParam(required = false) List<Integer> monthlyDays,
			@RequestParam(required = false) Integer yearlyMonth,
			@RequestParam(required = false) Integer yearlyDay,
			@RequestParam(defaultValue = "true") boolean enabled,
			RedirectAttributes redirect) {
		PolicyDraftCommand policy = new PolicyDraftCommand(name, checkInStartTime,
				toBands(bandLabel, bandStatus, bandUpperTime));
		if (startDate != null) {
			return command(
					() -> policyScheduleService.create(principal.toActor(), departmentId,
							new com.example.attend.attendance.application.PolicyScheduleCommand(
									policy, startDate, endDate,
									recurrence == null ? AttendanceDayRecurrence.NONE : recurrence,
									interval,
									toSet(weeklyDays, "반복 요일 값이 올바르지 않습니다."),
									toSet(monthlyDays, "반복 날짜 값이 올바르지 않습니다."),
									yearlyMonth, yearlyDay, enabled)),
					"출석 정책을 저장했습니다.", policiesPath(departmentId), redirect);
		}
		return command(
				() -> policyService.createDraft(
						principal.toActor(),
						departmentId,
						policy),
				"출석 정책 초안을 저장했습니다.",
				policiesPath(departmentId),
				redirect);
	}

	/** 알람처럼 정책 일정의 적용 여부를 전환한다. */
	@PostMapping("/admin/departments/{departmentId}/policies/{policyId}/enabled")
	public String setPolicyEnabled(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long policyId,
			@RequestParam boolean enabled,
			RedirectAttributes redirect) {
		return command(
				() -> policyScheduleService.setEnabled(
						principal.toActor(), departmentId, policyId, enabled),
				enabled ? "출석 정책을 활성화했습니다." : "출석 정책을 비활성화했습니다.",
				policiesPath(departmentId), redirect);
	}

	/** 과거 판정 이력을 보존하기 위해 정책을 보관 처리한다. */
	@PostMapping("/admin/departments/{departmentId}/policies/{policyId}/archive")
	public String archivePolicy(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long policyId,
			@RequestParam String reason,
			RedirectAttributes redirect) {
		return command(
				() -> policyScheduleService.archive(
						principal.toActor(), departmentId, policyId, reason),
				"출석 정책을 보관했습니다.", policiesPath(departmentId), redirect);
	}

	/** 알람형 정책 일정과 현재 시간 단계를 한 화면에서 수정한다. */
	@GetMapping("/admin/departments/{departmentId}/policies/{policyId}/edit")
	public String editPolicySchedule(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long policyId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		Map<String, Object> policy = queryService.policySchedule(principal.toActor(), departmentId, policyId);
		model.addAttribute("policy", policy);
		model.addAttribute("bands", queryService.policyBands(principal.toActor(), departmentId,
				((Number) policy.get("policy_version_id")).longValue()));
		model.addAttribute("weeklyDays", queryService.policyScheduleWeekdays(
				principal.toActor(), departmentId, policyId));
		model.addAttribute("monthlyDays", queryService.policyScheduleMonthdays(
				principal.toActor(), departmentId, policyId));
		return "admin/department/policy-schedule-edit";
	}

	@PostMapping("/admin/departments/{departmentId}/policies/{policyId}/edit")
	public String replacePolicySchedule(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long policyId,
			@RequestParam String name,
			@RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime checkInStartTime,
			@RequestParam(required = false) List<String> bandLabel,
			@RequestParam(required = false) List<AttendanceParentStatus> bandStatus,
			@RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") List<LocalTime> bandUpperTime,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam AttendanceDayRecurrence recurrence,
			@RequestParam(defaultValue = "1") int interval,
			@RequestParam(required = false) List<DayOfWeek> weeklyDays,
			@RequestParam(required = false) List<Integer> monthlyDays,
			@RequestParam(required = false) Integer yearlyMonth,
			@RequestParam(required = false) Integer yearlyDay,
			@RequestParam(defaultValue = "true") boolean enabled,
			RedirectAttributes redirect) {
		return command(() -> policyScheduleService.replace(principal.toActor(), departmentId, policyId,
				new com.example.attend.attendance.application.PolicyScheduleCommand(
						new PolicyDraftCommand(name, checkInStartTime, toBands(bandLabel, bandStatus, bandUpperTime)),
						startDate, endDate, recurrence, interval,
						toSet(weeklyDays, "반복 요일 값이 올바르지 않습니다."),
						toSet(monthlyDays, "반복 날짜 값이 올바르지 않습니다."),
						yearlyMonth, yearlyDay, enabled)),
				"출석 정책을 수정했습니다.", policiesPath(departmentId), redirect);
	}

	/** 발행 전 정책 초안과 단계를 전부 교체한다. */
	@PostMapping("/admin/departments/{departmentId}/policies/{policyId}/replace")
	public String replacePolicy(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long policyId,
			@RequestParam String name,
			@RequestParam @DateTimeFormat(pattern = "HH:mm")
					LocalTime checkInStartTime,
			@RequestParam(required = false) List<String> bandLabel,
			@RequestParam(required = false) List<AttendanceParentStatus> bandStatus,
			@RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm")
					List<LocalTime> bandUpperTime,
			RedirectAttributes redirect) {
		return command(
				() -> policyService.replaceDraft(
						principal.toActor(),
						departmentId,
						policyId,
						new PolicyDraftCommand(
								name,
								checkInStartTime,
								toBands(
										bandLabel,
										bandStatus,
										bandUpperTime))),
				"출석 정책 초안을 수정했습니다.",
				policiesPath(departmentId),
				redirect);
	}

	/** 전체 정책 규칙을 검증한 뒤 불변 발행 상태로 전환한다. */
	@PostMapping("/admin/departments/{departmentId}/policies/{policyId}/publish")
	public String publishPolicy(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long policyId,
			RedirectAttributes redirect) {
		return command(
				() -> policyService.publish(
						principal.toActor(), departmentId, policyId),
				"출석 정책을 발행했습니다.",
				policiesPath(departmentId),
				redirect);
	}

	/** 출석 날짜 목록과 발행 정책 선택 form을 표시한다. */
	@GetMapping("/admin/departments/{departmentId}/attendance-days")
	public String attendanceDays(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		model.addAttribute("days",
				queryService.attendanceDays(principal.toActor(), departmentId));
		model.addAttribute("publishedPolicies",
				queryService.publishedPolicies(
						principal.toActor(), departmentId));
		if (!model.containsAttribute("attendanceDayForm")) {
			model.addAttribute("attendanceDayForm", attendanceDayForm(
					null, null, null, AttendanceDayRecurrence.NONE,
					1, null, null, 1, 1));
		}
		return "admin/department/attendance-days";
	}

	/** 날짜 또는 반복 규칙으로 출석 날짜를 만들고 현재 활성 교사를 대상으로 snapshot한다. */
	@PostMapping("/admin/departments/{departmentId}/attendance-days")
	public String createAttendanceDay(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
					LocalDate attendanceDate,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
					LocalDate endDate,
			@RequestParam long policyVersionId,
			@RequestParam(defaultValue = "NONE")
					AttendanceDayRecurrence recurrence,
			@RequestParam(defaultValue = "1") int interval,
			@RequestParam(required = false) List<DayOfWeek> weeklyDays,
			@RequestParam(required = false) List<Integer> monthlyDays,
			@RequestParam(required = false) Integer yearlyMonth,
			@RequestParam(required = false) Integer yearlyDay,
			RedirectAttributes redirect) {
		Map<String, Object> submittedForm = attendanceDayForm(
				attendanceDate,
				endDate,
				policyVersionId,
				recurrence,
				interval,
				weeklyDays,
				monthlyDays,
				yearlyMonth,
				yearlyDay);
		try {
			writeGate.requireEnabled();
			String message;
			if (recurrence == AttendanceDayRecurrence.NONE) {
				dayService.createDay(
						principal.toActor(), departmentId, attendanceDate, policyVersionId);
				message = "출석 날짜를 생성했습니다.";
			} else {
				LocalDate effectiveEndDate = requireEndDate(endDate);
				AttendanceDayBatchResult result = dayService.createDays(
						principal.toActor(),
						departmentId,
						new AttendanceDayScheduleCommand(
								attendanceDate,
								effectiveEndDate,
								policyVersionId,
								recurrence,
								interval,
								toSet(
										weeklyDays,
										"반복 요일 값이 올바르지 않습니다."),
								toSet(
										monthlyDays,
										"반복 날짜 값이 올바르지 않습니다."),
								yearlyMonth,
								yearlyDay));
				message = attendanceDayBatchMessage(result);
			}
			redirect.addFlashAttribute("message", message);
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
			redirect.addFlashAttribute("attendanceDayForm", submittedForm);
		}
		return "redirect:" + daysPath(departmentId);
	}

	/** 한 날짜의 대상자와 출석 결과·정정 form을 표시한다. */
	@GetMapping("/admin/departments/{departmentId}/attendance-days/{dayId}")
	public String attendanceDay(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long dayId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		model.addAttribute("day", queryService.attendanceDay(
				principal.toActor(), departmentId, dayId));
		model.addAttribute("rows", queryService.attendanceRows(
				principal.toActor(), departmentId, dayId));
		model.addAttribute("teachers", queryService.teachers(
				principal.toActor(), departmentId));
		model.addAttribute("publishedPolicies",
				queryService.publishedPolicies(
						principal.toActor(), departmentId));
		return "admin/department/attendance-day";
	}

	/** 태깅 시작 전 기록 없는 출석 날짜를 취소한다. */
	@PostMapping("/admin/departments/{departmentId}/attendance-days/{dayId}/cancel")
	public String cancelDay(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long dayId,
			@RequestParam String reason,
			RedirectAttributes redirect) {
		return command(
				() -> dayService.cancelDay(
						principal.toActor(), departmentId, dayId, reason),
				"출석 날짜를 취소했습니다.",
				daysPath(departmentId),
				redirect);
	}

	/** 시작 전 날짜의 고정 정책을 다른 발행 버전으로 변경한다. */
	@PostMapping("/admin/departments/{departmentId}/attendance-days/{dayId}/change-policy")
	public String changeDayPolicy(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long dayId,
			@RequestParam long policyVersionId,
			RedirectAttributes redirect) {
		return command(
				() -> dayService.changePolicy(
						principal.toActor(),
						departmentId,
						dayId,
						policyVersionId),
				"출석 날짜의 정책을 변경했습니다.",
				dayPath(departmentId, dayId),
				redirect);
	}

	/** 시작 전 날짜에 활성 교사를 공식 대상으로 추가한다. */
	@PostMapping("/admin/departments/{departmentId}/attendance-days/{dayId}/targets/add")
	public String addTarget(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long dayId,
			@RequestParam long memberId,
			@RequestParam String reason,
			RedirectAttributes redirect) {
		return command(
				() -> targetService.addTarget(
						principal.toActor(),
						departmentId,
						dayId,
						memberId,
						reason),
				"출석 대상자를 추가했습니다.",
				dayPath(departmentId, dayId),
				redirect);
	}

	/** 기록 없는 시작 전 대상자를 이력 보존 방식으로 제외한다. */
	@PostMapping("/admin/departments/{departmentId}/attendance-days/{dayId}/targets/{memberId}/remove")
	public String removeTarget(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long dayId,
			@PathVariable long memberId,
			@RequestParam String reason,
			RedirectAttributes redirect) {
		return command(
				() -> targetService.removeTarget(
						principal.toActor(),
						departmentId,
						dayId,
						memberId,
						reason),
				"출석 대상자를 제외했습니다.",
				dayPath(departmentId, dayId),
				redirect);
	}

	/** 실제 시각 또는 결석 입력을 고정 정책으로 다시 계산해 저장한다. */
	@PostMapping("/admin/departments/{departmentId}/attendance-days/{dayId}/records/{memberId}/correct")
	public String correctAttendance(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long dayId,
			@PathVariable long memberId,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
					LocalDateTime actualCheckInAt,
			@RequestParam(defaultValue = "false") boolean markAbsent,
			@RequestParam(defaultValue = "false") boolean addMissingTarget,
			@RequestParam(required = false) String note,
			@RequestParam String reason,
			RedirectAttributes redirect) {
		return command(
				() -> correctionService.correct(
						principal.toActor(),
						departmentId,
						dayId,
						memberId,
						new ManualAttendanceCommand(
								actualCheckInAt == null
										? null
										: actualCheckInAt
												.atZone(attendanceZone)
												.toInstant(),
								markAbsent,
								addMissingTarget,
								note,
								reason)),
				"출석 기록을 저장했습니다.",
				dayPath(departmentId, dayId),
				redirect);
	}

	/** 기존 판정 원천을 바꾸지 않고 출석 비고만 수정한다. */
	@PostMapping("/admin/departments/{departmentId}/attendance-days/{dayId}/records/{memberId}/note")
	public String updateNote(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			@PathVariable long dayId,
			@PathVariable long memberId,
			@RequestParam(required = false) String note,
			@RequestParam String reason,
			RedirectAttributes redirect) {
		return command(
				() -> correctionService.updateNote(
						principal.toActor(),
						departmentId,
						dayId,
						memberId,
						note,
						reason),
				"출석 비고를 수정했습니다.",
				dayPath(departmentId, dayId),
				redirect);
	}

	/** 부서 범위 감사를 표시하고 로컬 데모에서만 태깅 이력을 추가한다. */
	@GetMapping("/admin/departments/{departmentId}/history")
	public String history(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			Model model) {
		addDepartmentModel(principal, departmentId, model);
		model.addAttribute("history",
				queryService.history(principal.toActor(), departmentId));
		model.addAttribute("showTagLogs", showTagLogs);
		if (showTagLogs) {
			model.addAttribute("tagHistory",
					queryService.tagHistory(principal.toActor(), departmentId));
		}
		return "admin/department/history";
	}

	private List<DashboardAttendanceRow> dashboardRows(
			AccountPrincipal principal,
			long departmentId,
			Map<String, Object> dashboard) {
		if (dashboard == null) {
			return List.of();
		}
		long attendanceDayId =
				((Number) dashboard.get("attendance_day_id")).longValue();
		return queryService.attendanceRows(
				principal.toActor(), departmentId, attendanceDayId).stream()
				.filter(row -> Boolean.TRUE.equals(row.get("is_target")))
				.map(DashboardAttendanceRow::from)
				.toList();
	}

	private void addDepartmentModel(
			AccountPrincipal principal,
			long departmentId,
			Model model) {
		model.addAttribute("principal", principal);
		model.addAttribute("department", queryService.department(
				principal.toActor(), departmentId));
		model.addAttribute("today", LocalDate.now(clock));
		model.addAttribute("writeEnabled", writeGate.isEnabled());
	}

	private String command(
			Runnable command,
			String successMessage,
			String redirectPath,
			RedirectAttributes redirect) {
		return command(
				command,
				successMessage,
				redirectPath,
				redirectPath,
				redirect);
	}

	private String command(
			Runnable command,
			String successMessage,
			String successRedirectPath,
			String failureRedirectPath,
			RedirectAttributes redirect) {
		try {
			writeGate.requireEnabled();
			command.run();
			redirect.addFlashAttribute("message", successMessage);
			return "redirect:" + successRedirectPath;
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirect.addFlashAttribute("error", exception.getMessage());
			return "redirect:" + failureRedirectPath;
		}
	}

	private static LocalDate requireEndDate(LocalDate endDate) {
		if (endDate == null) {
			throw new IllegalArgumentException("반복 종료일을 입력하세요.");
		}
		return endDate;
	}

	private static <T> Set<T> toSet(List<T> values, String invalidMessage) {
		if (values == null || values.isEmpty()) {
			return Set.of();
		}
		if (values.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException(invalidMessage);
		}
		return Set.copyOf(values);
	}

	private static Map<String, Object> attendanceDayForm(
			LocalDate attendanceDate,
			LocalDate endDate,
			Long policyVersionId,
			AttendanceDayRecurrence recurrence,
			int interval,
			List<DayOfWeek> weeklyDays,
			List<Integer> monthlyDays,
			Integer yearlyMonth,
			Integer yearlyDay
	) {
		Map<String, Object> form = new LinkedHashMap<>();
		form.put("attendanceDate", attendanceDate);
		form.put("endDate", endDate);
		form.put("policyVersionId", policyVersionId);
		form.put("recurrence", recurrence == null
				? AttendanceDayRecurrence.NONE.name()
				: recurrence.name());
		form.put("interval", interval);
		form.put("weeklyDays", weeklyDays == null
				? List.of()
				: weeklyDays.stream()
						.filter(Objects::nonNull)
						.map(Enum::name)
						.distinct()
						.toList());
		form.put("monthlyDays", monthlyDays == null
				? List.of()
				: monthlyDays.stream()
						.filter(Objects::nonNull)
						.distinct()
						.toList());
		form.put("yearlyMonth", yearlyMonth);
		form.put("yearlyDay", yearlyDay);
		return java.util.Collections.unmodifiableMap(form);
	}

	private static String attendanceDayBatchMessage(AttendanceDayBatchResult result) {
		if (result.skippedExistingCount() == 0) {
			return "출석 날짜 " + result.createdCount() + "건을 생성했습니다.";
		}
		return "출석 날짜 " + result.createdCount() + "건을 생성했고, 이미 존재하는 "
				+ result.skippedExistingCount() + "건은 건너뛰었습니다.";
	}

	private static List<PolicyBandInput> toBands(
			List<String> labels,
			List<AttendanceParentStatus> statuses,
			List<LocalTime> upperTimes) {
		labels = labels == null ? List.of() : labels;
		statuses = statuses == null ? List.of() : statuses;
		upperTimes = upperTimes == null ? List.of() : upperTimes;
		if (labels.size() != statuses.size()
				|| labels.size() != upperTimes.size()) {
			throw new IllegalArgumentException(
					"policy band fields must have the same number of rows");
		}
		List<PolicyBandInput> bands = new ArrayList<>();
		for (int index = 0; index < labels.size(); index++) {
			if (!labels.get(index).isBlank()) {
				bands.add(new PolicyBandInput(
						bands.size() + 1,
						labels.get(index),
						statuses.get(index),
						upperTimes.get(index)));
			}
		}
		return List.copyOf(bands);
	}

	private static NfcUid optionalUid(String raw) {
		return raw == null || raw.isBlank() ? null : new NfcUid(raw);
	}

	private static String teachersPath(long departmentId) {
		return "/admin/departments/" + departmentId + "/teachers";
	}

	private static String teacherPath(long departmentId, long memberId) {
		return teachersPath(departmentId) + "/" + memberId;
	}

	private static String teacherExcludePath(long departmentId, long memberId) {
		return teacherPath(departmentId, memberId) + "/exclude";
	}

	private static String policiesPath(long departmentId) {
		return "/admin/departments/" + departmentId + "/policies";
	}

	private static String daysPath(long departmentId) {
		return "/admin/departments/" + departmentId + "/attendance-days";
	}

	private static String dayPath(long departmentId, long dayId) {
		return daysPath(departmentId) + "/" + dayId;
	}
}
