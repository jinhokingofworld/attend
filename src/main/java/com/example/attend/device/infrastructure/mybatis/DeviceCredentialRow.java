package com.example.attend.device.infrastructure.mybatis;

/**
 * 장치 header 인증에만 사용하는 자격증명 projection이다.
 *
 * @param id 장치 식별자
 * @param departmentId 고정 부서 식별자
 * @param credentialHash HMAC-SHA-256 저장값
 * @param credentialVersion 현재 자격증명 세대
 */
public record DeviceCredentialRow(
		long id,
		long departmentId,
		String credentialHash,
		int credentialVersion) {
}
