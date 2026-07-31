package com.example.attend.device.infrastructure.mybatis;

/**
 * 인증 이후 업무 트랜잭션이 상태·세대 경합을 다시 검증할 장치 행이다.
 *
 * @param id 장치 식별자
 * @param departmentId 고정 부서 식별자
 * @param credentialVersion 현재 자격증명 세대
 * @param status 현재 장치 상태
 */
public record DeviceRuntimeRow(
		long id,
		long departmentId,
		int credentialVersion,
		String status) {
}
