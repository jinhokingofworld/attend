package com.example.attend.device.security;

import com.example.attend.device.application.DeviceAuthenticationService;
import com.example.attend.device.web.DeviceResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 정확히 한 쌍의 장치 header를 검증해 stateless Spring Security 인증을 만든다.
 */
public final class DeviceAuthenticationFilter extends OncePerRequestFilter {

	private static final String DEVICE_CODE_HEADER = "X-Device-Code";
	private static final String DEVICE_KEY_HEADER = "X-Device-Key";
	private final DeviceAuthenticationService authenticationService;
	private final DeviceResponseWriter responseWriter;

	/** 장치 인증 service와 오류 응답기를 받는다. */
	public DeviceAuthenticationFilter(
			DeviceAuthenticationService authenticationService,
			DeviceResponseWriter responseWriter) {
		this.authenticationService = authenticationService;
		this.responseWriter = responseWriter;
	}

	/** 누락·중복·불일치 원인을 구분하지 않는 동일한 401을 반환한다. */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String code = singleHeader(request, DEVICE_CODE_HEADER);
		String key = singleHeader(request, DEVICE_KEY_HEADER);
		DevicePrincipal principal = code == null || key == null
				? null
				: authenticationService.authenticate(code, key);
		if (principal == null) {
			responseWriter.write(
					response, 401, false, "DEVICE_UNAUTHORIZED",
					"장치 인증에 실패했습니다.", null, null);
			return;
		}
		UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(
						principal,
						null,
						List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		try {
			filterChain.doFilter(request, response);
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	private static String singleHeader(
			HttpServletRequest request,
			String name) {
		List<String> values = Collections.list(request.getHeaders(name));
		if (values.size() != 1) {
			return null;
		}
		String value = values.getFirst();
		if (value == null || value.isBlank() || value.length() > 200) {
			return null;
		}
		return value;
	}
}
