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

	/**
	 * 검증할 외부 관리자 보안 설정을 주입받는다.
	 *
	 * @param properties 관리자 보안 설정
	 */
	public ProductionAdminSecurityGuard(
			AdminSecurityProperties properties,
			DeviceApiProperties deviceProperties) {
		this.properties = properties;
		this.deviceProperties = deviceProperties;
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
	}
}
