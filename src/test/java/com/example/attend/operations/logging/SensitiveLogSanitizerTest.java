package com.example.attend.operations.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 민감정보 마스킹의 가장 중요한 필드명·자유 문자열 경계를 검증한다. */
class SensitiveLogSanitizerTest {

	/** 구조화 member 이름 자체가 민감하면 원문 형식과 무관하게 전부 제거한다. */
	@Test
	void masksSensitiveStructuredMember() {
		assertThat(SensitiveLogSanitizer.sanitize("device_key", "secret-value"))
				.isEqualTo(SensitiveLogSanitizer.REDACTED);
		assertThat(SensitiveLogSanitizer.sanitize("uid", "04A1B2C3"))
				.isEqualTo(SensitiveLogSanitizer.REDACTED);
		assertThat(SensitiveLogSanitizer.sanitize(
				"accountTokenPepper", "another-secret"))
				.isEqualTo(SensitiveLogSanitizer.REDACTED);
	}

	/** 예외 메시지에 섞인 token, UID와 전화번호도 그대로 출력하지 않는다. */
	@Test
	void masksSensitiveValuesInsideMessage() {
		String input = "token=abc123 uid=04A1B2C3 phone=010-1234-5678 "
				+ "jdbc:postgresql://runtime:db-password@example.test/attend";

		String masked = SensitiveLogSanitizer.sanitizeText(input);

		assertThat(masked)
				.doesNotContain(
						"abc123", "04A1B2C3", "010-1234-5678", "db-password")
				.contains("token=[REDACTED]", "uid=[REDACTED]");
	}
}
