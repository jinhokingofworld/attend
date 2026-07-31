package com.example.attend.access.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 관리자·계정 화면이 브라우저나 중간 cache에 저장되지 않게 한다.
 */
public final class SensitiveResponseHeaderFilter extends OncePerRequestFilter {

	/**
	 * 민감 화면 응답에 no-store와 referrer 차단 header를 설정한다.
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String path = request.getRequestURI().substring(
				request.getContextPath().length());
		if (path.startsWith("/admin")
				|| path.startsWith("/account/")
				|| path.equals("/login")) {
			response.setHeader("Cache-Control", "no-store");
			response.setHeader("Pragma", "no-cache");
			response.setHeader("Referrer-Policy", "no-referrer");
		}
		filterChain.doFilter(request, response);
	}
}
