package com.example.attend.device.security;

import com.example.attend.access.security.TokenBucketRateLimiter;
import com.example.attend.device.web.DeviceResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증된 장치별로 check-in과 credential-test bucket을 분리한다.
 */
public final class DeviceAuthenticatedRateLimitFilter
		extends OncePerRequestFilter {

	private final TokenBucketRateLimiter checkInLimiter;
	private final TokenBucketRateLimiter credentialTestLimiter;
	private final DeviceResponseWriter responseWriter;

	/** 문서의 check-in 10/초와 credential-test 2/20초 정책을 만든다. */
	public DeviceAuthenticatedRateLimitFilter(
			Clock clock,
			DeviceResponseWriter responseWriter) {
		this.checkInLimiter = new TokenBucketRateLimiter(10, 1_000, clock);
		this.credentialTestLimiter =
				new TokenBucketRateLimiter(2, 20_000, clock);
		this.responseWriter = responseWriter;
	}

	/** 장치 식별자만 bucket key에 사용하며 code와 원문 key는 저장하지 않는다. */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		Authentication authentication =
				SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication.getPrincipal() instanceof DevicePrincipal principal)) {
			filterChain.doFilter(request, response);
			return;
		}
		boolean credentialTest = request.getRequestURI().endsWith(
				"/credential-tests");
		boolean allowed = credentialTest
				? credentialTestLimiter.tryConsume(Long.toString(principal.deviceId()))
				: checkInLimiter.tryConsume(Long.toString(principal.deviceId()));
		if (!allowed) {
			response.setHeader("Retry-After", credentialTest ? "20" : "1");
			responseWriter.write(
					response, 429, false, "RATE_LIMITED",
					"요청이 너무 많습니다. 잠시 후 다시 시도하세요.", null, null);
			return;
		}
		filterChain.doFilter(request, response);
	}
}
