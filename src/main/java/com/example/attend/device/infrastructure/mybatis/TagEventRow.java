package com.example.attend.device.infrastructure.mybatis;

/**
 * 동일 requestId 재시도에 최초 결과를 재현하기 위한 event projection이다.
 *
 * @param uid 최초 요청 UID
 * @param httpStatus 최초 HTTP 상태
 * @param responseBody PostgreSQL jsonb가 정규화한 최초 JSON 본문
 */
public record TagEventRow(
		String uid,
		Integer httpStatus,
		String responseBody) {
}
