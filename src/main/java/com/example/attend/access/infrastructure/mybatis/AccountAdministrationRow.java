package com.example.attend.access.infrastructure.mybatis;

import java.time.Instant;

/**
 * 시스템 관리 화면과 상태 전이에 필요한 계정 조회 결과다.
 *
 * @param id 계정 식별자
 * @param username 사용자명
 * @param passwordHash 비밀번호 hash, 미설정이면 {@code null}
 * @param systemRole 시스템 역할, 없으면 {@code null}
 * @param status 계정 상태
 * @param passwordChangedAt 마지막 비밀번호 변경 시각
 * @param createdAt 계정 생성 시각
 */
public record AccountAdministrationRow(
		long id,
		String username,
		String passwordHash,
		String systemRole,
		String status,
		Instant passwordChangedAt,
		Instant createdAt) {

	/**
	 * 로그에 비밀번호 hash가 노출되지 않도록 식별자만 출력한다.
	 *
	 * @return 안전한 계정 설명
	 */
	@Override
	public String toString() {
		return "AccountAdministrationRow[id=%d]".formatted(id);
	}
}
