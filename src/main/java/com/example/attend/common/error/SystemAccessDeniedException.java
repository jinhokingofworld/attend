package com.example.attend.common.error;

import org.springframework.security.access.AccessDeniedException;

/**
 * 세션 역할과 무관하게 DB의 현재 시스템 역할 검사가 실패했음을 나타낸다.
 */
public final class SystemAccessDeniedException extends AccessDeniedException {

	/**
	 * 외부에 계정 상태를 노출하지 않는 고정 메시지를 사용한다.
	 */
	public SystemAccessDeniedException() {
		super("system administration is not allowed");
	}
}
