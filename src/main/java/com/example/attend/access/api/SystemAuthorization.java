package com.example.attend.access.api;

/**
 * 계정이 현재 활성 시스템 관리자인지 DB를 기준으로 확인하는 경계다.
 */
public interface SystemAuthorization {

	/**
	 * 활성 {@code SYSTEM_ADMIN}이 아니면 명령 실행을 거부한다.
	 *
	 * @param actor 인증 계정
	 */
	void requireSystemAdmin(AccountActor actor);
}
