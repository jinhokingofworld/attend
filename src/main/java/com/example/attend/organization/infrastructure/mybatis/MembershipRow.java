package com.example.attend.organization.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 잠긴 활성 소속 행에서 command가 필요로 하는 값만 담는다.
 *
 * @param id 소속 식별자
 * @param memberId 교사 식별자
 * @param joinedAt 소속 시작 시각
 * @param name 잠금 시점의 교사 이름
 * @param phone 잠금 시점의 교사 연락처
 * @param birth 잠금 시점의 교사 생년월일
 */
public record MembershipRow(
		long id,
		long memberId,
		Instant joinedAt,
		String name,
		String phone,
		LocalDate birth
) {
}
