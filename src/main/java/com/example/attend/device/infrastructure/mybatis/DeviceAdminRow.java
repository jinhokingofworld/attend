package com.example.attend.device.infrastructure.mybatis;

import java.time.Instant;

/**
 * 시스템 장치 관리 화면과 상태 전이에 필요한 비민감 장치 projection이다.
 *
 * @param id 장치 식별자
 * @param departmentId 고정 배정 부서 식별자
 * @param departmentName 부서 표시명
 * @param deviceCode 공개 장치 코드
 * @param name 장치 표시명
 * @param credentialVersion 현재 자격증명 세대
 * @param status 장치 상태
 * @param credentialIssuedAt 현재 자격증명 발급 시각
 * @param credentialTestedVersion 시험에 성공한 자격증명 세대
 * @param credentialTestedAt 시험 성공 시각
 * @param lastSeenAt 마지막 인증 성공 시각
 */
public record DeviceAdminRow(
		long id,
		long departmentId,
		String departmentName,
		String deviceCode,
		String name,
		int credentialVersion,
		String status,
		Instant credentialIssuedAt,
		Integer credentialTestedVersion,
		Instant credentialTestedAt,
		Instant lastSeenAt) {

	/**
	 * 현재 자격증명 세대가 발급 이후 실제 credential 시험을 통과했는지 계산한다.
	 *
	 * @return 현재 키의 유효한 시험 증거가 있으면 {@code true}
	 */
	public boolean currentCredentialTested() {
		return credentialTestedVersion != null
				&& credentialTestedVersion == credentialVersion
				&& credentialTestedAt != null
				&& !credentialTestedAt.isBefore(credentialIssuedAt);
	}
}
