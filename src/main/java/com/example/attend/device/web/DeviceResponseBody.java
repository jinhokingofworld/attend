package com.example.attend.device.web;

import java.time.OffsetDateTime;

/**
 * 모든 장치 API가 공유하는 개인정보 없는 JSON 응답 envelope다.
 *
 * @param success 업무 성공 여부
 * @param code 펌웨어 분기용 안정적인 코드
 * @param message 사람 진단용 비계약 문구
 * @param requestId 유효한 check-in 요청 ID, 신뢰 전이면 {@code null}
 * @param serverTime 응답을 확정한 서버 시각
 * @param data 응답별 제한된 데이터, 없으면 {@code null}
 */
public record DeviceResponseBody(
		boolean success,
		String code,
		String message,
		String requestId,
		OffsetDateTime serverTime,
		Object data) {
}
