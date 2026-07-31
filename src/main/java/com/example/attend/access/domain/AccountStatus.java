package com.example.attend.access.domain;

/**
 * 관리자 계정의 회원가입·로그인 생명주기 상태다.
 */
public enum AccountStatus {
	/** 비밀번호 설정 전 초대 대기 상태다. */
	PENDING_SETUP,
	/** 비밀번호가 있고 새 로그인이 허용된 상태다. */
	ACTIVE,
	/** 새 로그인이 차단된 보존 상태다. */
	DISABLED
}
