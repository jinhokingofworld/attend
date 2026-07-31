package com.example.attend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공개 Caddy와 애플리케이션 사이의 신뢰 경계를 설정한다.
 *
 * @param sharedToken Caddy가 upstream 요청에 덮어쓰는 내부 인증 token
 */
@ConfigurationProperties(prefix = "attendance.proxy")
public record TrustedProxyProperties(String sharedToken) {

	/** 공백 token을 미설정으로 정규화하고 실제 token의 바깥 공백을 제거한다. */
	public TrustedProxyProperties {
		if (sharedToken == null || sharedToken.isBlank()) {
			sharedToken = null;
		} else {
			sharedToken = sharedToken.trim();
		}
	}
}
