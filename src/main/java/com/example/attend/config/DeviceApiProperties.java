package com.example.attend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 장치 API availability와 자격증명 검증에 필요한 외부 설정이다.
 *
 * @param enabled 장치 API 요청을 받을지 여부
 * @param credentialPepper 장치 키 HMAC용 외부 비밀값
 */
@ConfigurationProperties(prefix = "device-api")
public record DeviceApiProperties(
		boolean enabled,
		String credentialPepper) {

	/**
	 * 공백뿐인 pepper를 미설정 값으로 정규화한다.
	 */
	public DeviceApiProperties {
		if (credentialPepper == null || credentialPepper.isBlank()) {
			credentialPepper = null;
		} else {
			credentialPepper = credentialPepper.trim();
		}
	}
}
