package com.example.attend.device.web;

/**
 * 엄격한 JSON 검증을 통과한 NFC 태깅 입력이다.
 *
 * @param uid 구분자 없는 대문자 4·7·10-byte UID
 * @param requestId 장치별 물리 태깅 식별자
 */
public record CheckInRequest(String uid, String requestId) {
}
