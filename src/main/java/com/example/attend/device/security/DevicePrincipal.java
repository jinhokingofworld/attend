package com.example.attend.device.security;

/**
 * 검증된 장치 header에서 만든 내부 인증 주체다.
 *
 * @param deviceId 내부 장치 식별자
 * @param departmentId 장치에 고정된 부서 식별자
 * @param credentialVersion 인증에 사용한 자격증명 세대
 */
public record DevicePrincipal(
		long deviceId,
		long departmentId,
		int credentialVersion) {
}
