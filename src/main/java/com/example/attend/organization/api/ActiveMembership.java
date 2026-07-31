package com.example.attend.organization.api;

import java.time.Instant;

/**
 * 출석 대상자 추가에 필요한 활성 소속의 최소 정보다.
 *
 * @param membershipId 소속 식별자
 * @param joinedAt 소속 시작 시각
 */
public record ActiveMembership(long membershipId, Instant joinedAt) {
}
