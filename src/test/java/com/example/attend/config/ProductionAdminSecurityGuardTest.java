package com.example.attend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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

	@Test
	void requiresDedicatedOperationsBotWhenAutomaticFinalizationIsEnabled() {
		ProductionAdminSecurityGuard guard = new ProductionAdminSecurityGuard(
				ADMIN_PROPERTIES,
				DEVICE_PROPERTIES,
				new TrustedProxyProperties(
						"proxy-token-that-is-at-least-32-bytes"),
				new TelegramProperties(
						false, null, null, null, null, 30_000, 10, 30),
				new AttendanceFinalizationSchedulerProperties(
						true, Duration.ofMinutes(2), Duration.ofMinutes(1), 20),
				new OperationalTelegramProperties(false, null, 0));

		assertThatThrownBy(guard::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("OPERATIONS_TELEGRAM_ENABLED");
	}

	@Test
	void acceptsSeparateOperationsBotForAutomaticFinalization() {
		ProductionAdminSecurityGuard guard = new ProductionAdminSecurityGuard(
				ADMIN_PROPERTIES,
				DEVICE_PROPERTIES,
				new TrustedProxyProperties(
						"proxy-token-that-is-at-least-32-bytes"),
				new TelegramProperties(
						true,
						"attendance-bot-token",
						"attendance_bot",
						"webhook-secret",
						"telegram-link-pepper-at-least-32-bytes",
						30_000,
						10,
						30),
				new AttendanceFinalizationSchedulerProperties(
						true, Duration.ofMinutes(2), Duration.ofMinutes(1), 20),
				new OperationalTelegramProperties(
						true, "operations-bot-token", -1001234567890L));

		assertThatCode(guard::validate).doesNotThrowAnyException();
	}

	@Test
	void rejectsReusingTheAttendanceBotForOperationsAlerts() {
		ProductionAdminSecurityGuard guard = new ProductionAdminSecurityGuard(
				ADMIN_PROPERTIES,
				DEVICE_PROPERTIES,
				new TrustedProxyProperties(
						"proxy-token-that-is-at-least-32-bytes"),
				new TelegramProperties(
						true,
						"shared-bot-token",
						"attendance_bot",
						"webhook-secret",
						"telegram-link-pepper-at-least-32-bytes",
						30_000,
						10,
						30),
				new AttendanceFinalizationSchedulerProperties(
						true, Duration.ofMinutes(2), Duration.ofMinutes(1), 20),
				new OperationalTelegramProperties(
						true, "shared-bot-token", -1001234567890L));

		assertThatThrownBy(guard::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("OPERATIONS_TELEGRAM_BOT_TOKEN");
	}

	private static ProductionAdminSecurityGuard guard(String proxyToken) {
		return new ProductionAdminSecurityGuard(
				ADMIN_PROPERTIES,
				DEVICE_PROPERTIES,
				new TrustedProxyProperties(proxyToken),
				new TelegramProperties(false, null, null, null, null, 30_000, 10, 30),
				new AttendanceFinalizationSchedulerProperties(
						false, Duration.ofMinutes(2), Duration.ofMinutes(1), 20),
				new OperationalTelegramProperties(false, null, 0));
	}
}
