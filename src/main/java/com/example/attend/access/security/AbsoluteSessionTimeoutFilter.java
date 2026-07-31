package com.example.attend.access.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 사용 중인 세션도 생성 후 일정 시간이 지나면 끝내는 절대 만료 필터다.
 *
 * <p>서블릿의 일반 세션 timeout은 마지막 요청부터 계산하는 유휴 만료다.
 * 그것만으로는 계속 요청하는 사용자의 세션이 무기한 유지될 수 있어, M3 정책인
 * 최대 8시간을 이 필터가 별도로 강제한다.</p>
 */
public final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

	/**
	 * 한 로그인 세션이 유지될 수 있는 최대 시간이다.
	 */
	public static final Duration MAX_SESSION_AGE = Duration.ofHours(8);

	private final Clock clock;

	/**
	 * 비교에 사용할 서버 시계를 주입받는다.
	 *
	 * @param clock 서버 시계
	 */
	public AbsoluteSessionTimeoutFilter(Clock clock) {
		this.clock = clock;
	}

	/**
	 * 세션 생성 시각이 8시간 이상 지났다면 인증과 세션을 폐기한다.
	 *
	 * @param request HTTP 요청
	 * @param response HTTP 응답
	 * @param filterChain 다음 필터
	 * @throws ServletException 다음 필터가 서블릿 오류를 낸 경우
	 * @throws IOException 응답 처리에 실패한 경우
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (isExpired(session)) {
			session.invalidate();
			SecurityContextHolder.clearContext();
			response.sendRedirect(request.getContextPath() + "/login?expired");
			return;
		}
		filterChain.doFilter(request, response);
	}

	private boolean isExpired(HttpSession session) {
		if (session == null) {
			return false;
		}
		Instant createdAt = Instant.ofEpochMilli(session.getCreationTime());
		return !clock.instant().isBefore(createdAt.plus(MAX_SESSION_AGE));
	}
}
