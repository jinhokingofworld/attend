package com.example.attend.operations.logging;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 운영 로그 문자열에서 인증정보와 교사 개인정보를 비가역 표시값으로 바꾼다.
 *
 * <p>로그에 민감값을 기록하지 않는 것이 첫 번째 방어선이다. 이 클래스는 예외
 * 메시지나 라이브러리 로그에 값이 우연히 포함되는 경우를 위한 마지막 방어선이다.</p>
 */
public final class SensitiveLogSanitizer {

	static final String REDACTED = "[REDACTED]";
	private static final String[] SENSITIVE_FIELD_MARKERS = {
			"password", "passwd", "token", "authorization",
			"devicekey", "apikey", "credential", "secret", "pepper",
			"dburl", "datasourceurl", "uid", "phone", "contact"
	};
	private static final Pattern NAMED_VALUE = Pattern.compile(
			"(?i)(password|passwd|token|authorization|device[-_ ]?key|credential|uid|phone|contact)"
					+ "(\\s*[=:]\\s*|\\\"\\s*:\\s*\\\")"
					+ "([^\\s,;}\\]\\\"]+)");
	private static final Pattern KOREAN_MOBILE = Pattern.compile(
			"(?<!\\d)01[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)");
	private static final Pattern NFC_UID = Pattern.compile(
			"(?i)(?<![0-9a-f])(?:[0-9a-f]{8}|[0-9a-f]{14}|[0-9a-f]{20})(?![0-9a-f])");
	private static final Pattern POSTGRES_USER_INFO = Pattern.compile(
			"(?i)((?:jdbc:)?postgresql://[^\\s/:@]+:)([^@\\s/]+)(@)");

	private SensitiveLogSanitizer() {
	}

	/** JSON member 이름과 값 양쪽을 확인해 로그에 쓸 안전한 값을 반환한다. */
	public static Object sanitize(String memberName, Object value) {
		if (value == null) {
			return null;
		}
		String normalizedName = memberName == null
				? ""
				: memberName.replace("-", "")
						.replace("_", "")
						.replace(".", "")
						.toLowerCase(Locale.ROOT);
		if (isSensitiveField(normalizedName)) {
			return REDACTED;
		}
		if (value instanceof String string) {
			return sanitizeText(string);
		}
		return value;
	}

	/** accountToken처럼 접두·접미사가 붙은 민감 member 이름도 놓치지 않는다. */
	private static boolean isSensitiveField(String normalizedName) {
		for (String marker : SENSITIVE_FIELD_MARKERS) {
			if (normalizedName.contains(marker)) {
				return true;
			}
		}
		return false;
	}

	/** 자유 형식 메시지 안의 이름 있는 비밀값, 연락처와 전체 UID를 제거한다. */
	static String sanitizeText(String input) {
		Matcher namedMatcher = NAMED_VALUE.matcher(input);
		String namedMasked = namedMatcher.replaceAll(
				match -> Matcher.quoteReplacement(
						match.group(1) + match.group(2) + REDACTED));
		String phoneMasked = KOREAN_MOBILE.matcher(namedMasked)
				.replaceAll(REDACTED);
		String uidMasked = NFC_UID.matcher(phoneMasked).replaceAll(REDACTED);
		return POSTGRES_USER_INFO.matcher(uidMasked).replaceAll(
				"$1" + REDACTED + "$3");
	}
}
