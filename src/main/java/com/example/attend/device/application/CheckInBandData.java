package com.example.attend.device.application;

/**
 * NFC 출석에 적용된 정책 구간의 비식별 snapshot이다.
 *
 * @param order 정책 안의 1부터 시작하는 순서
 * @param label 관리자 정의 구간 표시명
 */
public record CheckInBandData(int order, String label) {
}
