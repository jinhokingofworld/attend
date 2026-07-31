package com.example.attend.access.web;

import com.example.attend.access.application.OwnAccountService;
import com.example.attend.access.security.AccountPrincipal;
import com.example.attend.common.error.BusinessRuleException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 로그인 계정 자신의 비밀번호 변경 화면이다.
 */
@Controller
public final class OwnAccountController {

	private final OwnAccountService ownAccountService;

	/** 본인 비밀번호 변경 서비스를 주입받는다. */
	public OwnAccountController(OwnAccountService ownAccountService) {
		this.ownAccountService = ownAccountService;
	}

	/** 현재 계정의 비밀번호 변경 form을 표시한다. */
	@GetMapping("/admin/account/password")
	public String form() {
		return "admin/account-password";
	}

	/** 현재 비밀번호 확인 후 새 비밀번호를 저장한다. */
	@PostMapping("/admin/account/password")
	public String change(
			@AuthenticationPrincipal AccountPrincipal principal,
			@RequestParam String currentPassword,
			@RequestParam String newPassword,
			@RequestParam String passwordConfirmation,
			Model model) {
		try {
			ownAccountService.changePassword(
					principal.accountId(),
					currentPassword,
					newPassword,
					passwordConfirmation);
			model.addAttribute("message", "비밀번호를 변경했습니다.");
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			model.addAttribute("error", exception.getMessage());
		}
		return "admin/account-password";
	}
}
