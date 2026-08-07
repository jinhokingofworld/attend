package com.example.attend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 운영 Caddy token이 누락된 배포를 애플리케이션 기동 전에 차단하는지 검증한다. */
class ProductionAdminSecurityGuardTest {

	private static final AdminSecurityProperties ADMIN_PROPERTIES =
			new AdminSecurityProperties(
					false,
					"account-token-pepper-at-least-32-bytes",
					"https://attendance.example.test");
	private static final DeviceApiProperties DEVICE_PROPERTIES =
			new DeviceApiProperties(
					false, "device-pepper-that-is-at-least-32-bytes");

	/** 누락·짧은 proxy token은 운영 기동을 거부한다. */
	@Test
	void rejectsMissingOrShortTrustedProxyToken() {
		for (String token : new String[]{null, "too-short"}) {
			ProductionAdminSecurityGuard guard = guard(token);

			assertThatThrownBy(guard::validate)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("TRUSTED_PROXY_TOKEN");
		}
	}

	/** 세 종류의 독립 비밀값과 HTTPS URL이 유효하면 검증을 통과한다. */
	@Test
	void acceptsCompleteProductionSecurityConfiguration() {
		ProductionAdminSecurityGuard guard = guard(
				"proxy-token-that-is-at-least-32-bytes");

		assertThatCode(guard::validate).doesNotThrowAnyException();
	}

	private static ProductionAdminSecurityGuard guard(String proxyToken) {
		return new ProductionAdminSecurityGuard(
				ADMIN_PROPERTIES,
				DEVICE_PROPERTIES,
				new TrustedProxyProperties(proxyToken),
				new TelegramProperties(false, null, null, null, null, 30_000, 10, 30));
	}
}
