package com.example.attend.access.web;

import com.example.attend.access.application.CredentialTokenService;
import com.example.attend.access.domain.CredentialTokenPurpose;
import com.example.attend.common.error.BusinessRuleException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * URL fragment의 token으로 회원가입과 비밀번호 재설정을 완료하는 공개 화면이다.
 */
@Controller
public final class CredentialPageController {

	private final CredentialTokenService tokenService;

	/**
	 * token 소비 서비스를 주입받는다.
	 *
	 * @param tokenService 일회용 token 서비스
	 */
	public CredentialPageController(CredentialTokenService tokenService) {
		this.tokenService = tokenService;
	}

	/** 회원가입 초대 비밀번호 설정 form을 표시한다. */
	@GetMapping("/account/setup")
	public String setup(Model model, HttpServletResponse response) {
		return form(model, response, CredentialTokenPurpose.INVITATION);
	}

	/** 초대 token과 새 비밀번호를 POST body로 소비한다. */
	@PostMapping("/account/setup")
	public String completeSetup(
			@RequestParam String token,
			@RequestParam String password,
			@RequestParam String passwordConfirmation,
			Model model,
			HttpServletResponse response) {
		return consume(
				CredentialTokenPurpose.INVITATION,
				token,
				password,
				passwordConfirmation,
				model,
				response);
	}

	/** 비밀번호 재설정 form을 표시한다. */
	@GetMapping("/account/password-reset")
	public String reset(Model model, HttpServletResponse response) {
		return form(model, response, CredentialTokenPurpose.RESET);
	}

	/** 재설정 token과 새 비밀번호를 POST body로 소비한다. */
	@PostMapping("/account/password-reset")
	public String completeReset(
			@RequestParam String token,
			@RequestParam String password,
			@RequestParam String passwordConfirmation,
			Model model,
			HttpServletResponse response) {
		return consume(
				CredentialTokenPurpose.RESET,
				token,
				password,
				passwordConfirmation,
				model,
				response);
	}

	private String consume(
			CredentialTokenPurpose purpose,
			String token,
			String password,
			String confirmation,
			Model model,
			HttpServletResponse response) {
		noStore(response);
		try {
			tokenService.consume(purpose, token, password, confirmation);
			model.addAttribute("completed", true);
			model.addAttribute("purpose", purpose);
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			response.setStatus(400);
			model.addAttribute("error",
					"링크가 유효하지 않거나 입력을 확인할 수 없습니다.");
			model.addAttribute("purpose", purpose);
		}
		return "account/credential";
	}

	private String form(
			Model model,
			HttpServletResponse response,
			CredentialTokenPurpose purpose) {
		noStore(response);
		model.addAttribute("purpose", purpose);
		return "account/credential";
	}

	private static void noStore(HttpServletResponse response) {
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Referrer-Policy", "no-referrer");
	}
}
