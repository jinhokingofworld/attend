package com.example.attend.attendance.infrastructure.mybatis;

/**
 * 저장된 지각 단계 snapshot별 공식 건수다.
 *
 * @param sequenceNo 정책 구간 순서
 * @param label 저장 당시 단계명
 * @param count 건수
 */
public record AttendanceBandCountRow(int sequenceNo, String label, int count) {
}
