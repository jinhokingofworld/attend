package com.example.attend.device.security;

import com.example.attend.config.DeviceApiProperties;
import com.example.attend.device.web.DeviceResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 장치 API feature flag를 어떤 DB 인증이나 telemetry보다 먼저 검사한다.
 */
public final class DeviceApiAvailabilityFilter extends OncePerRequestFilter {

	private final DeviceApiProperties properties;
	private final DeviceResponseWriter responseWriter;

	/** availability 설정과 JSON 응답기를 받는다. */
	public DeviceApiAvailabilityFilter(
			DeviceApiProperties properties,
			DeviceResponseWriter responseWriter) {
		this.properties = properties;
		this.responseWriter = responseWriter;
	}

	/** 비활성 API는 DB에 접근하지 않고 재시도 가능한 503으로 종료한다. */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (!properties.enabled()) {
			response.setHeader("Retry-After", "5");
			responseWriter.write(
					response, 503, false, "SERVICE_UNAVAILABLE",
					"장치 API를 일시적으로 사용할 수 없습니다.", null, null);
			return;
		}
		filterChain.doFilter(request, response);
	}
}
