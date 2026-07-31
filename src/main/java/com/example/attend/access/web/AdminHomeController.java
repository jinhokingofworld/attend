package com.example.attend.access.web;

import com.example.attend.access.application.SystemAdministrationService;
import com.example.attend.access.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

/**
 * 로그인 직후 계정 역할에 맞는 관리자 진입점을 보여준다.
 */
@Controller
public final class AdminHomeController {

	private final SystemAdministrationService administrationService;

	/**
	 * 작업 공간 조회 서비스를 주입받는다.
	 *
	 * @param administrationService 시스템·부서 작업 공간 조회 서비스
	 */
	public AdminHomeController(
			SystemAdministrationService administrationService) {
		this.administrationService = administrationService;
	}

	/**
	 * 시스템 관리와 부서 관리 중 사용할 수 있는 작업 영역을 안내한다.
	 *
	 * @param principal 현재 로그인 계정
	 * @param model Thymeleaf 화면 모델
	 * @return 관리자 홈 template 이름
	 */
	@GetMapping("/admin")
	public String home(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		List<Map<String, Object>> workspaces =
				administrationService.workspaces(principal.toActor());
		boolean systemAdmin = principal.systemRole() != null;
		if (systemAdmin && workspaces.isEmpty()) {
			return "redirect:/admin/system";
		}
		if (!systemAdmin && workspaces.size() == 1) {
			return "redirect:/admin/departments/"
					+ workspaces.getFirst().get("department_id");
		}
		model.addAttribute("principal", principal);
		model.addAttribute("workspaces", workspaces);
		model.addAttribute("systemAdmin", systemAdmin);
		return "admin/workspaces";
	}

	/**
	 * 여러 역할을 가진 계정이 작업 공간을 명시적으로 선택하게 한다.
	 */
	@GetMapping("/admin/workspaces")
	public String workspaces(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		model.addAttribute("principal", principal);
		model.addAttribute("workspaces",
				administrationService.workspaces(principal.toActor()));
		model.addAttribute("systemAdmin", principal.systemRole() != null);
		return "admin/workspaces";
	}
}
