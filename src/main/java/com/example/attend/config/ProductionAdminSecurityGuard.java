package com.example.attend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 운영 profile이 안전한 token 비밀값과 공개 HTTPS URL 없이 기동되지 않게 한다.
 */
@Component
@Profile("prod")
public final class ProductionAdminSecurityGuard {

	private final AdminSecurityProperties properties;
	private final DeviceApiProperties deviceProperties;
	private final TrustedProxyProperties proxyProperties;
	private final TelegramProperties telegramProperties;

	/**
	 * 검증할 외부 관리자 보안 설정을 주입받는다.
	 *
	 * @param properties 관리자 보안 설정
	 * @param deviceProperties 장치 API 보안 설정
	 * @param proxyProperties Caddy 내부 신뢰 token 설정
	 */
	public ProductionAdminSecurityGuard(
			AdminSecurityProperties properties,
			DeviceApiProperties deviceProperties,
			TrustedProxyProperties proxyProperties,
			TelegramProperties telegramProperties) {
		this.properties = properties;
		this.deviceProperties = deviceProperties;
		this.proxyProperties = proxyProperties;
		this.telegramProperties = telegramProperties;
	}

	/**
	 * 빈 생성 직후 운영 필수값을 검증해 잘못된 배포를 fail-fast한다.
	 */
	@PostConstruct
	public void validate() {
		String pepper = properties.accountTokenPepper();
		if (pepper == null
				|| pepper.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(
					"ACCOUNT_TOKEN_PEPPER must contain at least 32 bytes in prod");
		}
		String baseUrl = properties.publicBaseUrl();
		URI uri;
		try {
			uri = baseUrl == null ? null : URI.create(baseUrl);
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException(
					"PUBLIC_BASE_URL must be a valid HTTPS origin in prod",
					exception);
		}
		if (uri == null
				|| !"https".equalsIgnoreCase(uri.getScheme())
				|| uri.getHost() == null
				|| uri.getUserInfo() != null
				|| uri.getQuery() != null
				|| uri.getFragment() != null) {
			throw new IllegalStateException(
					"PUBLIC_BASE_URL must be a valid HTTPS origin in prod");
		}
		String devicePepper = deviceProperties.credentialPepper();
		if (devicePepper == null
				|| devicePepper.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(
					"DEVICE_CREDENTIAL_PEPPER must contain at least 32 bytes in prod");
		}
		String proxyToken = proxyProperties.sharedToken();
		if (proxyToken == null
				|| proxyToken.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(
					"TRUSTED_PROXY_TOKEN must contain at least 32 bytes in prod");
		}
		if (telegramProperties.enabled()) {
			requireTelegramValue(telegramProperties.botToken(), "TELEGRAM_BOT_TOKEN");
			requireTelegramValue(telegramProperties.botUsername(), "TELEGRAM_BOT_USERNAME");
			requireTelegramValue(telegramProperties.webhookSecret(), "TELEGRAM_WEBHOOK_SECRET");
			String linkPepper = telegramProperties.linkTokenPepper();
			if (linkPepper == null
					|| linkPepper.getBytes(StandardCharsets.UTF_8).length < 32) {
				throw new IllegalStateException(
						"TELEGRAM_LINK_TOKEN_PEPPER must contain at least 32 bytes in prod");
			}
		}
	}

	private static void requireTelegramValue(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required when Telegram notifications are enabled");
		}
	}
}
