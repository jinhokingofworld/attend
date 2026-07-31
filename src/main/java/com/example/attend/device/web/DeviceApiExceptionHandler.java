package com.example.attend.device.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예상하지 못한 장치 API 예외를 내부 정보 없는 JSON 500으로 제한한다.
 */
@RestControllerAdvice(assignableTypes = DeviceApiController.class)
public final class DeviceApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(
			DeviceApiExceptionHandler.class);
	private final DeviceResponseWriter responseWriter;

	/** 공통 장치 응답기를 주입받는다. */
	public DeviceApiExceptionHandler(DeviceResponseWriter responseWriter) {
		this.responseWriter = responseWriter;
	}

	/**
	 * SQL·stack trace·자격증명을 노출하지 않고 재시도 가능한 서버 오류를 반환한다.
	 */
	@ExceptionHandler(Exception.class)
	public void handleUnexpected(
			Exception exception,
			HttpServletResponse response) throws IOException {
		log.error("Unhandled device API exception", exception);
		if (!response.isCommitted()) {
			responseWriter.write(
					response, 500, false, "SERVER_ERROR",
					"요청을 처리하지 못했습니다.", null, null);
		}
	}
}
