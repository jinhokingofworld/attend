package com.example.attend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M3 관리자 쓰기와 일회용 계정 token에 필요한 외부 설정이다.
 *
 * @param writeEnabled 관리자 업무 쓰기 허용 여부
 * @param accountTokenPepper token HMAC용 외부 비밀값
 * @param publicBaseUrl 초대·재설정 링크의 공개 HTTPS 기준 URL
 */
@ConfigurationProperties(prefix = "attendance.admin")
public record AdminSecurityProperties(
		boolean writeEnabled,
		String accountTokenPepper,
		String publicBaseUrl) {

	/**
	 * 설정값의 바깥 공백을 제거한다.
	 */
	public AdminSecurityProperties {
		accountTokenPepper = normalize(accountTokenPepper);
		publicBaseUrl = normalize(publicBaseUrl);
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
