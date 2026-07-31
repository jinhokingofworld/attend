package com.example.attend.access.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 로그인 요청을 사용자명 후보+source와 source 두 bucket으로 제한한다.
 */
public final class LoginRateLimitFilter extends OncePerRequestFilter {

	private final TokenBucketRateLimiter accountSourceLimiter;
	private final TokenBucketRateLimiter sourceLimiter;

	/**
	 * 문서의 5회·20회 burst와 분당 1회 회복 정책을 구성한다.
	 *
	 * @param clock 제한 시간 공급자
	 */
	public LoginRateLimitFilter(Clock clock) {
		this.accountSourceLimiter =
				new TokenBucketRateLimiter(5, 60_000, clock);
		this.sourceLimiter =
				new TokenBucketRateLimiter(20, 60_000, clock);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod())
				|| !"/authentication".equals(
						request.getRequestURI().substring(
								request.getContextPath().length()));
	}

	/**
	 * 비신뢰 proxy header를 무시하고 실제 remote address로 제한한다.
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String source = request.getRemoteAddr();
		String username = request.getParameter("usernameInput");
		String normalized = username == null
				? "" : username.trim().toLowerCase(Locale.ROOT);
		boolean sourceAllowed = sourceLimiter.tryConsume(source);
		boolean accountAllowed = accountSourceLimiter.tryConsume(
				sha256(normalized) + ":" + source);
		if (!sourceAllowed || !accountAllowed) {
			response.setStatus(429);
			response.setHeader("Retry-After", "60");
			response.setContentType("text/plain;charset=UTF-8");
			response.getWriter().write(
					"로그인 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
			return;
		}
		filterChain.doFilter(request, response);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256")
							.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
