package com.example.attend.access.security;

import com.example.attend.config.TrustedProxyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 내부 token으로 인증한 Caddy 요청에 한해서만 실제 client IP를 복원한다.
 *
 * <p>Caddy가 전달한 단일 {@code X-Forwarded-For}를 검증한 뒤
 * {@link HttpServletRequest#getRemoteAddr()}를 감싼다. 따라서 기존 로그인·공개
 * token·장치 사전 인증 rate limiter는 별도 header 해석 없이 client별 bucket을
 * 사용한다. 운영 token이 설정된 상태에서는 Caddy를 거치지 않은 요청을 거부한다.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class TrustedProxyClientIpFilter extends OncePerRequestFilter {

	static final String PROXY_TOKEN_HEADER = "X-Attend-Proxy-Token";
	static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
	private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:]{2,45}");
	private final byte[] expectedToken;

	/** 설정된 공유 token을 constant-time 비교용 byte 배열로 보관한다. */
	public TrustedProxyClientIpFilter(TrustedProxyProperties properties) {
		this.expectedToken = properties.sharedToken() == null
				? null
				: properties.sharedToken().getBytes(StandardCharsets.UTF_8);
	}

	/** 별도 loopback port의 Actuator health에는 공개 proxy token을 요구하지 않는다. */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		boolean healthPath = "/actuator/health".equals(path)
				|| path.startsWith("/actuator/health/");
		String remoteAddress = request.getRemoteAddr();
		return healthPath && ("127.0.0.1".equals(remoteAddress)
				|| "0:0:0:0:0:0:0:1".equals(remoteAddress)
				|| "::1".equals(remoteAddress));
	}

	/** 인증된 Caddy의 단일 IP만 remote address로 전달하고 나머지는 fail closed한다. */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (expectedToken == null) {
			filterChain.doFilter(request, response);
			return;
		}
		String providedToken = singleHeader(request, PROXY_TOKEN_HEADER);
		if (providedToken == null || !MessageDigest.isEqual(
				expectedToken, providedToken.getBytes(StandardCharsets.UTF_8))) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		String clientAddress = canonicalIpLiteral(
				singleHeader(request, FORWARDED_FOR_HEADER));
		if (clientAddress == null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		filterChain.doFilter(
				new ClientAddressRequest(request, clientAddress), response);
	}

	/** 누락·중복·빈 header를 신뢰하지 않고 정확히 한 값만 반환한다. */
	private static String singleHeader(HttpServletRequest request, String name) {
		List<String> values = Collections.list(request.getHeaders(name));
		if (values.size() != 1) {
			return null;
		}
		String value = values.getFirst();
		return value == null || value.isBlank() || value.length() > 200
				? null : value.trim();
	}

	/** DNS 조회가 가능한 hostname을 배제하고 IPv4·IPv6 literal만 canonicalize한다. */
	private static String canonicalIpLiteral(String value) {
		String canonicalIpv4 = canonicalIpv4(value);
		if (canonicalIpv4 != null) {
			return canonicalIpv4;
		}
		if (value == null || value.indexOf(':') < 0
				|| !IPV6_LITERAL.matcher(value).matches()) {
			return null;
		}
		try {
			return InetAddress.getByName(value).getHostAddress();
		} catch (UnknownHostException exception) {
			return null;
		}
	}

	/** 정확히 네 decimal octet인 IPv4만 허용하고 각 octet 표기를 정규화한다. */
	private static String canonicalIpv4(String value) {
		if (value == null) {
			return null;
		}
		String[] octets = value.split("\\.", -1);
		if (octets.length != 4) {
			return null;
		}
		StringBuilder canonical = new StringBuilder(15);
		for (int index = 0; index < octets.length; index++) {
			String octet = octets[index];
			if (octet.isEmpty() || octet.length() > 3
					|| !octet.chars().allMatch(
							character -> character >= '0' && character <= '9')) {
				return null;
			}
			int number = Integer.parseInt(octet);
			if (number > 255) {
				return null;
			}
			if (index > 0) {
				canonical.append('.');
			}
			canonical.append(number);
		}
		return canonical.toString();
	}

	/** downstream filter가 검증된 실제 client address를 보도록 요청을 감싼다. */
	private static final class ClientAddressRequest
			extends HttpServletRequestWrapper {

		private final String clientAddress;

		private ClientAddressRequest(
				HttpServletRequest request,
				String clientAddress) {
			super(request);
			this.clientAddress = clientAddress;
		}

		@Override
		public String getRemoteAddr() {
			return clientAddress;
		}

		@Override
		public String getRemoteHost() {
			return clientAddress;
		}

		/** 내부 proxy token은 검증 뒤 downstream controller에서 보이지 않게 제거한다. */
		@Override
		public String getHeader(String name) {
			return PROXY_TOKEN_HEADER.equalsIgnoreCase(name)
					? null : super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			return PROXY_TOKEN_HEADER.equalsIgnoreCase(name)
					? Collections.emptyEnumeration()
					: super.getHeaders(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			Enumeration<String> headerNames = super.getHeaderNames();
			if (headerNames == null) {
				return Collections.emptyEnumeration();
			}
			List<String> names = Collections.list(headerNames);
			names.removeIf(PROXY_TOKEN_HEADER::equalsIgnoreCase);
			return Collections.enumeration(names);
		}
	}
}
