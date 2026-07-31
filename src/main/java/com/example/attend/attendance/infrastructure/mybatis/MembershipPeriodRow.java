package com.example.attend.attendance.infrastructure.mybatis;

import java.time.Instant;

/**
 * 사후 대상자 추가 시 실제 출석 시각을 포함하는 소속 기간이다.
 *
 * @param id 소속 식별자
 * @param joinedAt 시작 시각
 * @param endedAt 종료 시각
 */
public record MembershipPeriodRow(long id, Instant joinedAt, Instant endedAt) {
}
