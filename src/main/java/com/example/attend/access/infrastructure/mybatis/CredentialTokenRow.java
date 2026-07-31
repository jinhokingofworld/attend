package com.example.attend.access.infrastructure.mybatis;

import java.time.Instant;

/**
 * 일회용 token 소비 시 잠그는 token·계정 상태다.
 *
 * @param tokenId token 식별자
 * @param accountId 대상 계정
 * @param accountStatus 대상 계정 상태
 * @param expiresAt token 만료 시각
 */
public record CredentialTokenRow(
		long tokenId,
		long accountId,
		String accountStatus,
		Instant expiresAt) {
}
