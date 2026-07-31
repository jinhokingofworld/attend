package com.example.attend.operations.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Spring Boot Logstash JSON의 모든 문자열 member에 공통 민감정보 마스킹을 적용한다.
 *
 * <p>로깅 시스템이 애플리케이션 context보다 먼저 시작되므로 Spring bean이 아니라
 * {@code logging.structured.json.customizer}에 클래스 이름으로 등록한다.</p>
 */
public final class SensitiveStructuredLoggingCustomizer
		implements StructuredLoggingJsonMembersCustomizer<Object> {

	/** JSON 직렬화 직전에 member 경로와 값을 검사하는 processor를 추가한다. */
	@Override
	public void customize(JsonWriter.Members<Object> members) {
		members.applyingValueProcessor(
				(path, value) -> SensitiveLogSanitizer.sanitize(
						path.name(), value));
	}
}
