package com.example.attend.device.application;

/**
 * credential 시험 성공 때 장치가 대조할 수 있는 최소 상태 데이터다.
 *
 * @param deviceStatus 시험을 허용한 INACTIVE 상태
 * @param credentialVersion 시험한 자격증명 세대
 */
public record CredentialTestData(
		String deviceStatus,
		int credentialVersion) {
}
