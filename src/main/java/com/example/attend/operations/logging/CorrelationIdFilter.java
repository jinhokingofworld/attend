package com.example.attend.operations.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 각 HTTP 요청에 서버가 생성한 상관관계 ID를 부여해 운영 장애 추적을 돕는다.
 *
 * <p>클라이언트 값을 신뢰하면 로그 위조가 가능하므로 요청 header는 재사용하지
 * 않는다. 값은 응답 header와 같은 요청에서 발생한 구조화 로그 MDC에만 넣는다.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

	static final String RESPONSE_HEADER = "X-Correlation-ID";
	static final String MDC_KEY = "correlationId";

	/** 요청 처리 동안 UUID를 MDC에 넣고 finally에서 반드시 제거한다. */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String correlationId = UUID.randomUUID().toString();
		response.setHeader(RESPONSE_HEADER, correlationId);
		try (MDC.MDCCloseable ignored =
				MDC.putCloseable(MDC_KEY, correlationId)) {
			filterChain.doFilter(request, response);
		}
	}
}
