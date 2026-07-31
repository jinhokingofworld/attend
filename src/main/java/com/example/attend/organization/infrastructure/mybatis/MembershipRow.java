package com.example.attend.organization.infrastructure.mybatis;

import java.time.Instant;

/**
 * 잠긴 활성 소속 행에서 command가 필요로 하는 값만 담는다.
 *
 * @param id 소속 식별자
 * @param memberId 교사 식별자
 * @param joinedAt 소속 시작 시각
 */
public record MembershipRow(long id, long memberId, Instant joinedAt) {
}
