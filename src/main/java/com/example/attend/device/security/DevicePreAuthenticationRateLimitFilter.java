package com.example.attend.device.security;

import com.example.attend.access.security.TokenBucketRateLimiter;
import com.example.attend.device.web.DeviceResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DB 인증 전에 실제 remote address별 요청 burst를 제한한다.
 */
public final class DevicePreAuthenticationRateLimitFilter
		extends OncePerRequestFilter {

	private final TokenBucketRateLimiter limiter;
	private final DeviceResponseWriter responseWriter;

	/** 문서의 source별 capacity 20, 초당 1 token 정책을 만든다. */
	public DevicePreAuthenticationRateLimitFilter(
			Clock clock,
			DeviceResponseWriter responseWriter) {
		this.limiter = new TokenBucketRateLimiter(20, 1_000, clock);
		this.responseWriter = responseWriter;
	}

	/**
	 * 신뢰 proxy가 아직 설정되지 않은 MVP에서는 forwarded header를 무시한다.
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (!limiter.tryConsume(request.getRemoteAddr())) {
			response.setHeader("Retry-After", "1");
			responseWriter.write(
					response, 429, false, "RATE_LIMITED",
					"요청이 너무 많습니다. 잠시 후 다시 시도하세요.", null, null);
			return;
		}
		filterChain.doFilter(request, response);
	}
}
