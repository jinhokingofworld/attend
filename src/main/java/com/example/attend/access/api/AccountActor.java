package com.example.attend.access.api;

/**
 * 관리자 업무를 요청한 인증 계정을 나타낸다.
 *
 * <p>이 값은 HTTP form이나 request body에서 만들지 않는다. M3에서 Spring Security가
 * 인증한 principal의 계정 ID로 생성해야 하며, M2의 application service 테스트에서는
 * 명시적인 fixture로 전달한다.</p>
 *
 * @param accountId 인증된 계정의 DB 식별자
 */
public record AccountActor(long accountId) {

	/**
	 * 유효한 DB 식별자만 actor로 사용할 수 있도록 보장한다.
	 */
	public AccountActor {
		if (accountId <= 0) {
			throw new IllegalArgumentException("accountId must be positive");
		}
	}
}
