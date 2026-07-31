package com.example.attend.access.infrastructure.mybatis;

/**
 * Spring Security 인증에 필요한 계정 정보만 담는 조회 결과다.
 *
 * @param id 계정 식별자
 * @param username DB에 저장된 정규 사용자명
 * @param passwordHash BCrypt 비밀번호 해시
 * @param systemRole 시스템 역할 문자열, 역할이 없으면 {@code null}
 * @param departmentAdmin 활성 부서 관리자 역할을 하나 이상 보유했는지 여부
 */
public record AccountSecurityRow(
		long id,
		String username,
		String passwordHash,
		String systemRole,
		boolean departmentAdmin) {

	/**
	 * 로그에 비밀번호 해시가 실수로 기록되지 않도록 안전한 문자열만 반환한다.
	 *
	 * @return 식별 가능한 최소 계정 정보
	 */
	@Override
	public String toString() {
		return "AccountSecurityRow[id=%d, username=%s]"
				.formatted(id, username);
	}
}
