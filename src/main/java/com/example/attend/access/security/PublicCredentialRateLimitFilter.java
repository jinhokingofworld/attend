package com.example.attend.access.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * 공개 초대·재설정 POST의 무차별 token 시도를 source별로 제한한다.
 */
public final class PublicCredentialRateLimitFilter
		extends OncePerRequestFilter {

	private final TokenBucketRateLimiter limiter;

	/**
	 * source별 10회 burst와 분당 1회 회복 bucket을 만든다.
	 *
	 * @param clock 제한 시간 공급자
	 */
	public PublicCredentialRateLimitFilter(Clock clock) {
		this.limiter = new TokenBucketRateLimiter(10, 60_000, clock);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!"POST".equals(request.getMethod())) {
			return true;
		}
		String path = request.getRequestURI().substring(
				request.getContextPath().length());
		return !"/account/setup".equals(path)
				&& !"/account/password-reset".equals(path);
	}

	/**
	 * 요청 source만 사용하며 token 원문을 제한 key에 넣지 않는다.
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (!limiter.tryConsume(request.getRemoteAddr())) {
			response.setStatus(429);
			response.setHeader("Retry-After", "60");
			response.setContentType("text/plain;charset=UTF-8");
			response.getWriter().write(
					"요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
			return;
		}
		filterChain.doFilter(request, response);
	}
}
