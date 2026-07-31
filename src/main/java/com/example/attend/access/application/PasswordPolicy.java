package com.example.attend.access.application;

import java.nio.charset.StandardCharsets;

/**
 * MVP 비밀번호의 길이와 BCrypt 입력 byte 상한을 검사한다.
 */
public final class PasswordPolicy {

	/** 허용되는 최소 Unicode code point 수다. */
	public static final int MIN_CODE_POINTS = 12;
	/** 허용되는 최대 Unicode code point 수다. */
	public static final int MAX_CODE_POINTS = 64;
	/** BCrypt가 잘림 없이 처리할 수 있는 UTF-8 최대 byte 수다. */
	public static final int MAX_UTF8_BYTES = 72;

	private PasswordPolicy() {
	}

	/**
	 * 비밀번호와 확인값이 정책을 만족하는지 검사한다.
	 *
	 * @param password 새 비밀번호
	 * @param confirmation 비밀번호 확인값
	 */
	public static void validate(String password, String confirmation) {
		if (password == null || confirmation == null) {
			throw new IllegalArgumentException("password and confirmation are required");
		}
		if (!password.equals(confirmation)) {
			throw new IllegalArgumentException("password confirmation does not match");
		}
		int codePoints = password.codePointCount(0, password.length());
		if (codePoints < MIN_CODE_POINTS || codePoints > MAX_CODE_POINTS) {
			throw new IllegalArgumentException(
					"password must contain 12 to 64 Unicode characters");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
			throw new IllegalArgumentException(
					"password must not exceed 72 UTF-8 bytes");
		}
	}
}
