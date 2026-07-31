package com.example.attend.access.web;

import com.example.attend.access.application.AdminWriteGate;
import com.example.attend.access.application.CredentialTokenService;
import com.example.attend.access.application.IssuedCredentialLink;
import com.example.attend.access.application.SystemAdministrationService;
import com.example.attend.access.domain.CredentialTokenPurpose;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationRow;
import com.example.attend.access.security.AccountPrincipal;
import com.example.attend.common.error.BusinessRuleException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 시스템 관리자의 부서·계정·역할 MVC 화면을 제공한다.
 */
@Controller
public final class SystemAdminController {

	private final SystemAdministrationService administrationService;
	private final CredentialTokenService tokenService;
	private final AdminWriteGate writeGate;

	/**
	 * 시스템 관리자 화면의 application service를 주입받는다.
	 */
	public SystemAdminController(
			SystemAdministrationService administrationService,
			CredentialTokenService tokenService,
			AdminWriteGate writeGate) {
		this.administrationService = administrationService;
		this.tokenService = tokenService;
		this.writeGate = writeGate;
	}

	/** 시스템 관리 요약 화면을 표시한다. */
	@GetMapping("/admin/system")
	public String home(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("departments",
				administrationService.departments(principal.toActor()));
		model.addAttribute("accounts",
				administrationService.accounts(principal.toActor()));
		return "admin/system/home";
	}

	/** 부서 목록을 표시한다. */
	@GetMapping("/admin/system/departments")
	public String departments(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("departments",
				administrationService.departments(principal.toActor()));
		return "admin/system/departments";
	}

	/** 부서 생성 form을 표시한다. */
	@GetMapping("/admin/system/departments/new")
	public String newDepartment(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		return "admin/system/department-new";
	}

	/** 계정·권한과 분리해 부서 한 건만 생성한다. */
	@PostMapping("/admin/system/departments")
	public String createDepartment(
			@AuthenticationPrincipal AccountPrincipal principal,
			@RequestParam String name,
			RedirectAttributes redirectAttributes) {
		try {
			long id = administrationService.createDepartment(
					principal.toActor(), name);
			redirectAttributes.addFlashAttribute(
					"message", "부서를 생성했습니다.");
			return "redirect:/admin/system/departments/" + id;
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
			redirectAttributes.addFlashAttribute("name", name);
			return "redirect:/admin/system/departments/new";
		}
	}

	/** 부서의 시스템 관리용 상세를 표시한다. */
	@GetMapping("/admin/system/departments/{departmentId}")
	public String department(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long departmentId,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("department",
				administrationService.department(
						principal.toActor(), departmentId));
		model.addAttribute("administrators",
				administrationService.departmentAdministrators(
						principal.toActor(), departmentId));
		model.addAttribute("devices",
				administrationService.departmentDevices(
						principal.toActor(), departmentId));
		return "admin/system/department-detail";
	}

	/** 관리자 계정 목록을 표시한다. */
	@GetMapping("/admin/system/accounts")
	public String accounts(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("accounts",
				administrationService.accounts(principal.toActor()));
		return "admin/system/accounts";
	}

	/**
	 * DB에서 계산한 개인정보 없는 운영 집계를 보여준다.
	 */
	@GetMapping("/admin/system/operations")
	public String operations(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("operations",
				administrationService.operations(principal.toActor()));
		return "admin/system/operations";
	}

	/**
	 * 시스템 lifecycle action만 포함한 감사 이력을 보여준다.
	 */
	@GetMapping("/admin/system/audit")
	public String audit(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("history",
				administrationService.systemAudit(principal.toActor()));
		return "admin/system/audit";
	}

	/** 초대 대기 계정 생성 form을 표시한다. */
	@GetMapping("/admin/system/accounts/new")
	public String newAccount(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		return "admin/system/account-new";
	}

	/** 비밀번호 없는 초대 대기 계정을 생성한다. */
	@PostMapping("/admin/system/accounts")
	public String createAccount(
			@AuthenticationPrincipal AccountPrincipal principal,
			@RequestParam String username,
			@RequestParam(defaultValue = "false") boolean systemAdmin,
			RedirectAttributes redirectAttributes) {
		try {
			long id = administrationService.createAccount(
					principal.toActor(), username, systemAdmin);
			redirectAttributes.addFlashAttribute(
					"message", "초대 대기 계정을 생성했습니다.");
			return "redirect:/admin/system/accounts/" + id;
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
			redirectAttributes.addFlashAttribute("username", username);
			return "redirect:/admin/system/accounts/new";
		}
	}

	/** 계정 상태와 현재 부서 권한을 표시한다. */
	@GetMapping("/admin/system/accounts/{accountId}")
	public String account(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long accountId,
			Model model) {
		addCommon(principal, model);
		AccountAdministrationRow account = administrationService.account(
				principal.toActor(), accountId);
		model.addAttribute("account", account);
		model.addAttribute("roles", administrationService.roles(
				principal.toActor(), accountId));
		model.addAttribute("departments", administrationService.departments(
				principal.toActor()));
		return "admin/system/account-detail";
	}

	/** 회원가입 초대 링크를 새로 발급해 한 번만 표시한다. */
	@PostMapping("/admin/system/accounts/{accountId}/invite")
	public String invite(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long accountId,
			Model model) {
		return showCredentialLink(
				principal,
				accountId,
				CredentialTokenPurpose.INVITATION,
				model);
	}

	/** 비밀번호 재설정 링크를 새로 발급해 한 번만 표시한다. */
	@PostMapping("/admin/system/accounts/{accountId}/reset-password")
	public String resetPassword(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long accountId,
			Model model) {
		return showCredentialLink(
				principal,
				accountId,
				CredentialTokenPurpose.RESET,
				model);
	}

	/** 확인 사용자명이 일치하는 계정을 비활성화한다. */
	@PostMapping("/admin/system/accounts/{accountId}/disable")
	public String disable(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long accountId,
			@RequestParam String usernameConfirmation,
			RedirectAttributes redirectAttributes) {
		try {
			administrationService.disableAccount(
					principal.toActor(), accountId, usernameConfirmation);
			redirectAttributes.addFlashAttribute("message", "계정을 비활성화했습니다.");
		} catch (BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
		}
		return "redirect:/admin/system/accounts/" + accountId;
	}

	/** 비활성 계정을 이전 자격증명 상태로 되돌린다. */
	@PostMapping("/admin/system/accounts/{accountId}/enable")
	public String enable(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long accountId,
			RedirectAttributes redirectAttributes) {
		try {
			administrationService.enableAccount(principal.toActor(), accountId);
			redirectAttributes.addFlashAttribute("message", "계정을 활성화했습니다.");
		} catch (BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
		}
		return "redirect:/admin/system/accounts/" + accountId;
	}

	/** 계정에 부서 관리자 역할을 명시적으로 배정한다. */
	@PostMapping("/admin/system/accounts/{accountId}/department-roles")
	public String assignRole(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long accountId,
			@RequestParam long departmentId,
			RedirectAttributes redirectAttributes) {
		try {
			administrationService.assignDepartmentRole(
					principal.toActor(), accountId, departmentId);
			redirectAttributes.addFlashAttribute("message", "부서 권한을 지정했습니다.");
		} catch (BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
		}
		return "redirect:/admin/system/accounts/" + accountId;
	}

	/** 계정의 지정 부서 관리자 역할을 회수한다. */
	@PostMapping("/admin/system/accounts/{accountId}/department-roles/{departmentId}/revoke")
	public String revokeRole(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long accountId,
			@PathVariable long departmentId,
			RedirectAttributes redirectAttributes) {
		administrationService.revokeDepartmentRole(
				principal.toActor(), accountId, departmentId);
		redirectAttributes.addFlashAttribute("message", "부서 권한을 회수했습니다.");
		return "redirect:/admin/system/accounts/" + accountId;
	}

	private String showCredentialLink(
			AccountPrincipal principal,
			long accountId,
			CredentialTokenPurpose purpose,
			Model model) {
		addCommon(principal, model);
		IssuedCredentialLink issued = tokenService.issue(
				principal.toActor(), accountId, purpose);
		model.addAttribute("issued", issued);
		return "admin/system/credential-link";
	}

	private void addCommon(AccountPrincipal principal, Model model) {
		model.addAttribute("principal", principal);
		model.addAttribute("writeEnabled", writeGate.isEnabled());
	}

	private static String safeMessage(RuntimeException exception) {
		return exception.getMessage() == null
				? "요청을 처리할 수 없습니다."
				: exception.getMessage();
	}
}
