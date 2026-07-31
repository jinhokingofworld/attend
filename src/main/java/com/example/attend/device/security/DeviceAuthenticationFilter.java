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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 정확히 한 쌍의 장치 header를 검증해 stateless Spring Security 인증을 만든다.
 */
public final class DeviceAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(
			DeviceAuthenticationFilter.class);
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
		DevicePrincipal principal;
		try {
			/*
			 * Header 유효성에 따라 인증 호출 자체를 생략하면 정적 분석기가 인증
			 * 우회로 판단할 수 있다. 누락·중복 header도 인증 service가 동일하게
			 * 실패시키도록 항상 호출한다.
			 */
			principal = authenticationService.authenticate(code, key);
		} catch (RuntimeException exception) {
			log.error("Unexpected device authentication failure", exception);
			responseWriter.write(
					response, 500, false, "SERVER_ERROR",
					"요청을 처리하지 못했습니다.", null, null);
			return;
		}
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

	/**
	 * 같은 이름의 header가 정확히 한 개이며 길이 제한 안에 있을 때만 값을 반환한다.
	 *
	 * <p>Servlet의 {@code getHeader}만 사용하면 중복 header의 첫 값만 채택할 수 있어
	 * proxy와 서버가 서로 다른 값을 해석하는 문제가 생길 수 있다.</p>
	 *
	 * @param request 검사할 장치 요청
	 * @param name header 이름
	 * @return 단일 유효 값, 누락·중복·공백·초과면 {@code null}
	 */
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
