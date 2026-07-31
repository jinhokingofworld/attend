package com.example.attend.access.api;

/**
 * 관리자 업무 쓰기의 전역 feature 상태를 확인하는 경계다.
 */
public interface AdminWriteAuthorization {

	/**
	 * 운영자가 쓰기를 중지한 상태이면 명령을 거부한다.
	 */
	void requireEnabled();

	/**
	 * 관리자 화면 배너에 사용할 현재 쓰기 상태다.
	 *
	 * @return 쓰기가 허용되면 {@code true}
	 */
	boolean isEnabled();
}
