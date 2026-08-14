package com.example.attend.access.application;

import com.example.attend.access.api.AccountActor;
import com.example.attend.access.api.SystemAuthorization;
import com.example.attend.access.domain.AccountStatus;
import com.example.attend.access.domain.CredentialTokenPurpose;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationMapper;
import com.example.attend.access.infrastructure.mybatis.AccountAdministrationRow;
import com.example.attend.access.infrastructure.mybatis.CredentialTokenRow;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.common.error.BusinessRuleException;
import com.example.attend.config.AdminSecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * 회원가입 초대와 비밀번호 재설정 token의 30분·1회 생명주기를 관리한다.
 */
@Service
public class CredentialTokenService {

	private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
	private static final int TOKEN_BYTES = 32;
	private static final int MIN_PEPPER_BYTES = 32;

	private final SystemAuthorization systemAuthorization;
	private final AdminWriteGate writeGate;
	private final AccountAdministrationMapper mapper;
	private final AuditLogWriter auditLogWriter;
	private final PasswordEncoder passwordEncoder;
	private final AdminSecurityProperties properties;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	/**
	 * token 유스케이스의 권한·저장·암호화 협력 객체를 주입받는다.
	 */
	public CredentialTokenService(
			SystemAuthorization systemAuthorization,
			AdminWriteGate writeGate,
			AccountAdministrationMapper mapper,
			AuditLogWriter auditLogWriter,
			PasswordEncoder passwordEncoder,
			AdminSecurityProperties properties,
			Clock clock) {
		this.systemAuthorization = systemAuthorization;
		this.writeGate = writeGate;
		this.mapper = mapper;
		this.auditLogWriter = auditLogWriter;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 기존 활성 token을 무효화하고 새 원문 링크를 한 번만 반환한다.
	 *
	 * @param actor 발급 시스템 관리자
	 * @param accountId 대상 계정
	 * @param purpose 초대 또는 재설정
	 * @return 다시 조회할 수 없는 원문 링크
	 */
	@Transactional
	public IssuedCredentialLink issue(
			AccountActor actor,
			long accountId,
			CredentialTokenPurpose purpose) {
		writeGate.requireEnabled();
		systemAuthorization.requireSystemAdmin(actor);
		return issueUnchecked(actor.accountId(), accountId, purpose);
	}

	/**
	 * 초대 outbox worker만 사용하는 내부 발급 경로다. 요청 시점의 권한은
	 * outbox를 만들 때 검증됐고, 이 메서드는 원문을 저장하지 않은 채 메일 전송
	 * 시점마다 새 초대 token을 만든다.
	 */
	@Transactional
	public IssuedCredentialLink issueInvitationForDelivery(
			long issuerAccountId, long accountId) {
		writeGate.requireEnabled();
		return issueUnchecked(issuerAccountId, accountId,
				CredentialTokenPurpose.INVITATION);
	}

	private IssuedCredentialLink issueUnchecked(
			long issuerAccountId,
			long accountId,
			CredentialTokenPurpose purpose) {
		String baseUrl = requirePublicBaseUrl();
		byte[] pepper = requirePepper();

		AccountAdministrationRow account = mapper.lockAccount(accountId);
		if (account == null || !expectedStatus(purpose).name().equals(account.status())) {
			throw new BusinessRuleException("account cannot receive this credential token");
		}

		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(TOKEN_LIFETIME);
		mapper.revokeActiveTokens(accountId, purpose.name(), issuedAt);

		byte[] rawBytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(rawBytes);
		String rawToken = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(rawBytes);
		mapper.insertCredentialToken(
				accountId,
				purpose.name(),
				hash(rawToken, pepper),
				issuerAccountId,
				issuedAt,
				expiresAt);
		auditLogWriter.writeAccount(
				null,
				new AccountActor(issuerAccountId),
				null,
				purpose == CredentialTokenPurpose.INVITATION
						? "ACCOUNT_INVITATION_ISSUED"
						: "ACCOUNT_PASSWORD_RESET_ISSUED",
				"ACCOUNT",
				Long.toString(accountId),
				null,
				Map.of("purpose", purpose.name(), "expiresAt", expiresAt.toString()),
				null);
		return new IssuedCredentialLink(
				purpose,
				baseUrl + purpose.path() + "#token=" + rawToken,
				expiresAt);
	}

	/**
	 * 공개 페이지가 제출한 token을 소비하고 비밀번호를 원자적으로 저장한다.
	 *
	 * @param purpose token 목적
	 * @param rawToken URL fragment에서 POST body로 옮긴 원문 token
	 * @param password 새 비밀번호
	 * @param confirmation 비밀번호 확인
	 */
	@Transactional
	public void consume(
			CredentialTokenPurpose purpose,
			String rawToken,
			String password,
			String confirmation) {
		writeGate.requireEnabled();
		PasswordPolicy.validate(password, confirmation);
		if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) {
			throw genericTokenFailure();
		}

		CredentialTokenRow token = mapper.lockCredentialToken(
				hash(rawToken, requirePepper()),
				purpose.name());
		Instant consumedAt = clock.instant();
		if (token == null
				|| !consumedAt.isBefore(token.expiresAt())
				|| !expectedStatus(purpose).name().equals(token.accountStatus())) {
			throw genericTokenFailure();
		}

		String passwordHash = passwordEncoder.encode(password);
		int changed = purpose == CredentialTokenPurpose.INVITATION
				? mapper.activateInvitedAccount(
						token.accountId(), passwordHash, consumedAt)
				: mapper.resetActiveAccountPassword(
						token.accountId(), passwordHash, consumedAt);
		if (changed != 1
				|| mapper.consumeCredentialToken(token.tokenId(), consumedAt) != 1) {
			throw genericTokenFailure();
		}
		auditLogWriter.writeAccount(
				null,
				new AccountActor(token.accountId()),
				null,
				purpose == CredentialTokenPurpose.INVITATION
						? "ACCOUNT_SETUP_COMPLETED"
						: "ACCOUNT_PASSWORD_RESET_COMPLETED",
				"ACCOUNT",
				Long.toString(token.accountId()),
				null,
				Map.of("passwordChanged", true),
				null);
	}

	private static AccountStatus expectedStatus(CredentialTokenPurpose purpose) {
		return purpose == CredentialTokenPurpose.INVITATION
				? AccountStatus.PENDING_SETUP
				: AccountStatus.ACTIVE;
	}

	private byte[] requirePepper() {
		String pepper = properties.accountTokenPepper();
		if (pepper == null
				|| pepper.getBytes(StandardCharsets.UTF_8).length < MIN_PEPPER_BYTES) {
			throw new BusinessRuleException(
					"account token pepper is not safely configured");
		}
		return pepper.getBytes(StandardCharsets.UTF_8);
	}

	private String requirePublicBaseUrl() {
		String baseUrl = properties.publicBaseUrl();
		if (baseUrl == null) {
			throw new BusinessRuleException("public base URL is not configured");
		}
		URI uri;
		try {
			uri = URI.create(baseUrl);
		} catch (IllegalArgumentException exception) {
			throw new BusinessRuleException("public base URL is invalid");
		}
		if (!"https".equalsIgnoreCase(uri.getScheme())
				|| uri.getHost() == null
				|| uri.getUserInfo() != null
				|| uri.getQuery() != null
				|| uri.getFragment() != null) {
			throw new BusinessRuleException(
					"public base URL must be an HTTPS origin");
		}
		return baseUrl.endsWith("/")
				? baseUrl.substring(0, baseUrl.length() - 1)
				: baseUrl;
	}

	private static String hash(String rawToken, byte[] pepper) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
			return HexFormat.of().formatHex(
					mac.doFinal(rawToken.getBytes(StandardCharsets.US_ASCII)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
		}
	}

	private static BusinessRuleException genericTokenFailure() {
		return new BusinessRuleException(
				"credential link is invalid or expired");
	}
}
