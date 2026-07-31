package com.example.attend.device.application;

import java.time.Instant;

/**
 * 장치 생성 또는 키 교체 직후 한 번만 관리자에게 전달할 원문 자격증명이다.
 *
 * @param deviceId 내부 장치 식별자
 * @param deviceCode 실제 장치의 공개 식별 코드
 * @param deviceKey 다시 복원할 수 없는 원문 비밀키
 * @param credentialVersion 발급된 자격증명 세대
 * @param issuedAt 서버 발급 시각
 */
public record IssuedDeviceCredential(
		long deviceId,
		String deviceCode,
		String deviceKey,
		int credentialVersion,
		Instant issuedAt) {
}
