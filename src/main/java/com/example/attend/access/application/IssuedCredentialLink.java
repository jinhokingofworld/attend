package com.example.attend.access.application;

import com.example.attend.access.domain.CredentialTokenPurpose;

import java.time.Instant;

/**
 * 발급 응답 한 번에만 존재하는 초대·재설정 링크다.
 */
public final class IssuedCredentialLink {

	private final CredentialTokenPurpose purpose;
	private final String link;
	private final Instant expiresAt;

	/**
	 * 원문 token 링크와 만료 시각을 묶는다.
	 *
	 * @param purpose token 목적
	 * @param link URL fragment에 원문 token이 든 링크
	 * @param expiresAt 만료 시각
	 */
	public IssuedCredentialLink(
			CredentialTokenPurpose purpose,
			String link,
			Instant expiresAt) {
		this.purpose = purpose;
		this.link = link;
		this.expiresAt = expiresAt;
	}

	/** @return 발급 token의 목적 */
	public CredentialTokenPurpose purpose() {
		return purpose;
	}

	/** @return 이번 응답에서만 표시할 원문 링크 */
	public String link() {
		return link;
	}

	/** @return 링크 만료 시각 */
	public Instant expiresAt() {
		return expiresAt;
	}

	/**
	 * 원문 token이 로그에 들어가지 않게 링크를 출력하지 않는다.
	 *
	 * @return 목적과 만료만 포함한 설명
	 */
	@Override
	public String toString() {
		return "IssuedCredentialLink[purpose=%s, expiresAt=%s]"
				.formatted(purpose, expiresAt);
	}
}
