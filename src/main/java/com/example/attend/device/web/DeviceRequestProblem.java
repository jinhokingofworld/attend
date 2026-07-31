package com.example.attend.device.web;

/**
 * 장치 요청이 업무 service에 도달하기 전에 실패한 입력 문제다.
 *
 * @param httpStatus 반환할 HTTP 상태
 * @param code OpenAPI에 고정된 응답 코드
 * @param message 개인정보 없는 진단 문구
 */
public record DeviceRequestProblem(
		int httpStatus,
		String code,
		String message) {
}
