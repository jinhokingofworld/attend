package com.example.attend.device.application;

/**
 * application service가 확정한 장치 HTTP 상태와 JSON 본문이다.
 *
 * @param httpStatus 반환할 HTTP 상태
 * @param responseBody 개인정보를 제외한 canonical JSON 본문
 */
public record DeviceHttpResult(int httpStatus, String responseBody) {
}
