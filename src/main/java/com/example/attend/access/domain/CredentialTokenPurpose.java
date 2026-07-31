package com.example.attend.access.domain;

/**
 * 일회용 계정 자격증명 token의 허용된 목적이다.
 */
public enum CredentialTokenPurpose {
	/** 초대받은 신규 계정의 최초 비밀번호 설정이다. */
	INVITATION("/account/setup"),
	/** 활성 계정의 비밀번호 재설정이다. */
	RESET("/account/password-reset");

	private final String path;

	CredentialTokenPurpose(String path) {
		this.path = path;
	}

	/**
	 * token을 소비하는 고정 공개 경로다.
	 *
	 * @return 공개 상대 경로
	 */
	public String path() {
		return path;
	}
}
