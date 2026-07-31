package com.example.attend.device.web;

import com.example.attend.device.application.DeviceCheckInService;
import com.example.attend.device.application.DeviceCredentialTestService;
import com.example.attend.device.application.DeviceHttpResult;
import com.example.attend.device.application.DeviceRequestStateService;
import com.example.attend.device.application.DeviceStateChangedException;
import com.example.attend.device.security.DevicePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAPI에 고정된 credential 시험과 NFC check-in 장치 endpoint를 제공한다.
 */
@RestController
public final class DeviceApiController {

	private static final int CREDENTIAL_BODY_DRAIN_LIMIT = 1024;
	private final DeviceCredentialTestService credentialTestService;
	private final DeviceCheckInService checkInService;
	private final DeviceRequestStateService requestStateService;
	private final DeviceCheckInRequestParser requestParser;
	private final DeviceResponseWriter responseWriter;
	private final Clock clock;

	/** 장치 application service, strict parser와 응답기를 주입받는다. */
	public DeviceApiController(
			DeviceCredentialTestService credentialTestService,
			DeviceCheckInService checkInService,
			DeviceRequestStateService requestStateService,
			DeviceCheckInRequestParser requestParser,
			DeviceResponseWriter responseWriter,
			Clock clock) {
		this.credentialTestService = credentialTestService;
		this.checkInService = checkInService;
		this.requestStateService = requestStateService;
		this.requestParser = requestParser;
		this.responseWriter = responseWriter;
		this.clock = clock;
	}

	/** body가 없는 실제 INACTIVE 장치의 현재 자격증명을 시험한다. */
	@PostMapping("/api/v1/device/credential-tests")
	public void credentialTest(
			@AuthenticationPrincipal DevicePrincipal principal,
			HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (hasUnexpectedBody(request)) {
			responseWriter.write(
					response, 400, false, "UNEXPECTED_BODY",
					"이 요청에는 body를 보낼 수 없습니다.", null, null);
			return;
		}
		DeviceHttpResult result = credentialTestService.test(
				principal, clock.instant());
		responseWriter.write(
				response, result.httpStatus(), result.responseBody());
	}

	/** 엄격히 검증한 UID와 requestId를 하나의 멱등 출석 transaction에 전달한다. */
	@PostMapping("/api/v1/device/check-ins")
	public void checkIn(
			@AuthenticationPrincipal DevicePrincipal principal,
			HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (!requestStateService.checkInAllowed(principal)) {
			responseWriter.write(
					response, 409, false, "DEVICE_NOT_ACTIVE",
					"출석에 사용할 수 없는 장치 상태입니다.", null, null);
			return;
		}
		ParsedCheckIn parsed = requestParser.parse(request);
		if (!parsed.successful()) {
			DeviceRequestProblem problem = parsed.problem();
			responseWriter.write(
					response,
					problem.httpStatus(),
					false,
					problem.code(),
					problem.message(),
					null,
					null);
			return;
		}
		try {
			DeviceHttpResult result = checkInService.checkIn(
					principal, parsed.request(), clock.instant());
			responseWriter.write(
					response, result.httpStatus(), result.responseBody());
		} catch (DeviceStateChangedException exception) {
			responseWriter.write(
					response, 409, false, "DEVICE_STATE_CHANGED",
					"처리 중 장치 상태가 변경되었습니다.",
					exception.requestId(), null);
		}
	}

	/**
	 * credential-test의 body를 최대 1 KiB까지 배출해 작은 잘못된 요청에서도
	 * keep-alive 연결을 재사용할 수 있게 한다.
	 *
	 * @param request 검사할 servlet 요청
	 * @return 한 byte라도 body가 있으면 {@code true}
	 * @throws IOException body를 읽지 못한 경우
	 */
	private static boolean hasUnexpectedBody(HttpServletRequest request)
			throws IOException {
		InputStream input = request.getInputStream();
		byte[] buffer = new byte[256];
		int total = 0;
		while (total <= CREDENTIAL_BODY_DRAIN_LIMIT) {
			int read = input.read(
					buffer,
					0,
					Math.min(
							buffer.length,
							CREDENTIAL_BODY_DRAIN_LIMIT + 1 - total));
			if (read < 0) {
				break;
			}
			total += read;
		}
		return total > 0 || request.getContentLengthLong() > 0;
	}
}
