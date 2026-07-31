package com.example.attend.organization.domain;

import java.util.Locale;
import java.util.Set;

/**
 * MFRC522가 읽은 NFC UID의 정규화된 표현이다.
 *
 * @param value 구분자 없는 대문자 16진수 UID
 */
public record NfcUid(String value) {

	private static final Set<Integer> SUPPORTED_LENGTHS = Set.of(8, 14, 20);

	/**
	 * 공백과 구분자는 허용하지 않고 4·7·10 byte UID만 받는다.
	 */
	public NfcUid {
		if (value == null) {
			throw new IllegalArgumentException("UID must not be null");
		}
		value = value.toUpperCase(Locale.ROOT);
		if (!SUPPORTED_LENGTHS.contains(value.length())
				|| !value.matches("[0-9A-F]+")) {
			throw new IllegalArgumentException(
					"UID must be 4, 7, or 10 bytes of hexadecimal without separators");
		}
	}

	/**
	 * 감사와 일반 로그에 사용할 끝 네 자리 마스킹 값을 만든다.
	 *
	 * @return 별표와 UID 끝 네 자리
	 */
	public String masked() {
		return "****" + value.substring(value.length() - 4);
	}
}
