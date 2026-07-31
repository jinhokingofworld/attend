package com.example.attend.device.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 장치 API 응답을 공통 envelope와 cache 금지 header로 직렬화한다.
 */
@Component
public final class DeviceResponseWriter {

	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final ZoneId attendanceZone;

	/**
	 * Spring JSON 설정과 서버 시각 구성을 주입받는다.
	 */
	public DeviceResponseWriter(
			ObjectMapper objectMapper,
			Clock clock,
			ZoneId attendanceZone) {
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.attendanceZone = attendanceZone;
	}

	/** 현재 서버 시각을 사용해 장치 오류를 즉시 쓴다. */
	public void write(
			HttpServletResponse response,
			int status,
			boolean success,
			String code,
			String message,
			String requestId,
			Object data) throws IOException {
		write(response, status, serialize(body(
				success, code, message, requestId, clock.instant(), data)));
	}

	/** 이미 canonical JSON으로 확정된 멱등 응답을 그대로 쓴다. */
	public void write(
			HttpServletResponse response,
			int status,
			String canonicalJson) throws IOException {
		prepare(response, status);
		response.getWriter().write(canonicalJson);
	}

	/** 지정한 단일 수신 시각으로 envelope 객체를 만든다. */
	public DeviceResponseBody body(
			boolean success,
			String code,
			String message,
			String requestId,
			Instant serverTime,
			Object data) {
		return new DeviceResponseBody(
				success,
				code,
				message,
				requestId,
				OffsetDateTime.ofInstant(serverTime, attendanceZone),
				data);
	}

	/** 저장 가능한 JSON 문자열로 공통 envelope를 직렬화한다. */
	public String serialize(DeviceResponseBody body) {
		try {
			return objectMapper.writeValueAsString(body);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("device response could not be serialized", exception);
		}
	}

	/** 모든 장치 응답에 JSON·UTF-8·no-store 경계를 동일하게 설정한다. */
	private static void prepare(HttpServletResponse response, int status) {
		response.setStatus(status);
		response.setCharacterEncoding("UTF-8");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader("Cache-Control", "no-store");
	}
}
