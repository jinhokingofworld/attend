package com.example.attend.device.web;

import com.example.attend.access.application.AdminWriteGate;
import com.example.attend.access.application.SystemAdministrationService;
import com.example.attend.access.security.AccountPrincipal;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.device.application.DeviceManagementService;
import com.example.attend.device.application.IssuedDeviceCredential;
import com.example.attend.device.infrastructure.mybatis.DeviceAdminRow;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 시스템 관리자의 장치 수명주기와 비밀키 1회 표시 화면을 제공한다.
 */
@Controller
public final class DeviceAdminController {

	private static final String CREDENTIAL_SESSION_PREFIX =
			"deviceCredentialOnce:";
	private static final Duration CREDENTIAL_DISPLAY_TTL =
			Duration.ofMinutes(10);
	private final DeviceManagementService deviceService;
	private final SystemAdministrationService administrationService;
	private final AdminWriteGate writeGate;
	private final Clock clock;

	/**
	 * 장치 관리와 부서 선택에 필요한 application service를 주입받는다.
	 */
	public DeviceAdminController(
			DeviceManagementService deviceService,
			SystemAdministrationService administrationService,
			AdminWriteGate writeGate,
			Clock clock) {
		this.deviceService = deviceService;
		this.administrationService = administrationService;
		this.writeGate = writeGate;
		this.clock = clock;
	}

	/** 장치 목록을 비밀키 없이 표시한다. */
	@GetMapping("/admin/system/devices")
	public String devices(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("devices", deviceService.devices(principal.toActor()));
		return "admin/system/devices";
	}

	/** 장치 상세와 현재 키의 시험 상태를 표시한다. */
	@GetMapping("/admin/system/devices/{deviceId}")
	public String device(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long deviceId,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("device",
				deviceService.device(principal.toActor(), deviceId));
		return "admin/system/device-detail";
	}

	/** 활성 부서 중 하나를 고르는 장치 등록 form을 표시한다. */
	@GetMapping("/admin/system/devices/new")
	public String newDevice(
			@AuthenticationPrincipal AccountPrincipal principal,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("departments",
				administrationService.departments(principal.toActor()));
		return "admin/system/device-new";
	}

	/**
	 * INACTIVE 장치를 생성하고 원문 키를 URL·flash가 아닌 서버 세션에 잠시 보관한다.
	 */
	@PostMapping("/admin/system/devices")
	public String createDevice(
			@AuthenticationPrincipal AccountPrincipal principal,
			@RequestParam long departmentId,
			@RequestParam String deviceCode,
			@RequestParam String name,
			HttpSession session,
			RedirectAttributes redirectAttributes) {
		try {
			IssuedDeviceCredential issued = deviceService.create(
					principal.toActor(), departmentId, deviceCode, name);
			storeOnce(session, issued);
			return "redirect:/admin/system/devices/"
					+ issued.deviceId() + "/credential-once";
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
			return "redirect:/admin/system/devices/new";
		}
	}

	/**
	 * 세션에서 원문 키를 먼저 제거한 뒤 no-store 응답으로 정확히 한 번 표시한다.
	 */
	@GetMapping("/admin/system/devices/{deviceId}/credential-once")
	public String credentialOnce(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long deviceId,
			HttpSession session,
			HttpServletResponse response,
			Model model,
			RedirectAttributes redirectAttributes) {
		deviceService.device(principal.toActor(), deviceId);
		Object value;
		synchronized (session) {
			value = session.getAttribute(sessionKey(deviceId));
			session.removeAttribute(sessionKey(deviceId));
		}
		if (!(value instanceof IssuedDeviceCredential issued)
				|| issued.deviceId() != deviceId
				|| Duration.between(issued.issuedAt(), clock.instant())
						.compareTo(CREDENTIAL_DISPLAY_TTL) > 0) {
			redirectAttributes.addFlashAttribute(
					"error",
					"비밀키는 다시 표시할 수 없습니다. 필요하면 키를 교체하세요.");
			return "redirect:/admin/system/devices/" + deviceId;
		}
		response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
		response.setHeader("Pragma", "no-cache");
		addCommon(principal, model);
		model.addAttribute("issued", issued);
		return "admin/system/device-credential-once";
	}

	/** 현재 credential 시험을 통과한 INACTIVE 장치를 활성화한다. */
	@PostMapping("/admin/system/devices/{deviceId}/activate")
	public String activate(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long deviceId,
			RedirectAttributes redirectAttributes) {
		try {
			deviceService.activate(principal.toActor(), deviceId);
			redirectAttributes.addFlashAttribute("message", "장치를 활성화했습니다.");
		} catch (BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
		}
		return "redirect:/admin/system/devices/" + deviceId;
	}

	/** 활성 장치를 즉시 비활성화하고 현재 시험 증거를 제거한다. */
	@PostMapping("/admin/system/devices/{deviceId}/deactivate")
	public String deactivate(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long deviceId,
			@RequestParam String reason,
			RedirectAttributes redirectAttributes) {
		try {
			deviceService.deactivate(principal.toActor(), deviceId, reason);
			redirectAttributes.addFlashAttribute("message", "장치를 비활성화했습니다.");
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
		}
		return "redirect:/admin/system/devices/" + deviceId;
	}

	/** 키 교체의 영향과 확인 입력 form을 표시한다. */
	@GetMapping("/admin/system/devices/{deviceId}/rotate-credential")
	public String rotateCredentialForm(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long deviceId,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("device",
				deviceService.device(principal.toActor(), deviceId));
		return "admin/system/device-rotate";
	}

	/** 새 키를 발급하고 원문을 1회 화면으로 전달한다. */
	@PostMapping("/admin/system/devices/{deviceId}/rotate-credential")
	public String rotateCredential(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long deviceId,
			@RequestParam String deviceCodeConfirmation,
			@RequestParam String reason,
			HttpSession session,
			RedirectAttributes redirectAttributes) {
		try {
			IssuedDeviceCredential issued = deviceService.rotateCredential(
					principal.toActor(),
					deviceId,
					deviceCodeConfirmation,
					reason);
			storeOnce(session, issued);
			return "redirect:/admin/system/devices/"
					+ deviceId + "/credential-once";
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
			return "redirect:/admin/system/devices/"
					+ deviceId + "/rotate-credential";
		}
	}

	/** 종결 상태 전이 전 장치 코드와 영향 확인 form을 표시한다. */
	@GetMapping("/admin/system/devices/{deviceId}/revoke")
	public String revokeForm(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long deviceId,
			Model model) {
		addCommon(principal, model);
		model.addAttribute("device",
				deviceService.device(principal.toActor(), deviceId));
		return "admin/system/device-revoke";
	}

	/** 확인값을 검증하고 장치를 영구 폐기한다. */
	@PostMapping("/admin/system/devices/{deviceId}/revoke")
	public String revoke(
			@AuthenticationPrincipal AccountPrincipal principal,
			@PathVariable long deviceId,
			@RequestParam String deviceCodeConfirmation,
			@RequestParam String reason,
			RedirectAttributes redirectAttributes) {
		try {
			deviceService.revoke(
					principal.toActor(),
					deviceId,
					deviceCodeConfirmation,
					reason);
			redirectAttributes.addFlashAttribute("message", "장치를 폐기했습니다.");
			return "redirect:/admin/system/devices/" + deviceId;
		} catch (IllegalArgumentException | BusinessRuleException exception) {
			redirectAttributes.addFlashAttribute("error", safeMessage(exception));
			return "redirect:/admin/system/devices/" + deviceId + "/revoke";
		}
	}

	/** 시스템 장치 화면이 공통으로 사용하는 인증 주체와 쓰기 상태를 추가한다. */
	private void addCommon(AccountPrincipal principal, Model model) {
		model.addAttribute("principal", principal);
		model.addAttribute("writeEnabled", writeGate.isEnabled());
	}

	/** 원문 키를 URL이나 flash에 넣지 않고 현재 서버 세션에 한 번만 보관한다. */
	private static void storeOnce(
			HttpSession session,
			IssuedDeviceCredential issued) {
		session.setAttribute(sessionKey(issued.deviceId()), issued);
	}

	/** 여러 장치 탭의 1회 키가 서로 덮어쓰이지 않도록 장치별 session key를 만든다. */
	private static String sessionKey(long deviceId) {
		return CREDENTIAL_SESSION_PREFIX + deviceId;
	}

	/** 메시지가 없는 업무 예외를 사용자용 일반 문구로 바꾼다. */
	private static String safeMessage(RuntimeException exception) {
		return exception.getMessage() == null
				? "요청을 처리할 수 없습니다."
				: exception.getMessage();
	}
}
