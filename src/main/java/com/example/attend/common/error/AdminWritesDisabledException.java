package com.example.attend.common.error;

/**
 * 운영 feature flag로 관리자 쓰기가 일시 중지되었음을 나타낸다.
 */
public final class AdminWritesDisabledException extends RuntimeException {

	/**
	 * 외부 상태와 무관한 고정 메시지를 사용한다.
	 */
	public AdminWritesDisabledException() {
		super("administrator writes are temporarily unavailable");
	}
}
