package com.example.attend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 출석 마감 worker의 lease, batch와 장애 복구 설정이다. */
@ConfigurationProperties(prefix = "attendance.scheduler")
public record AttendanceFinalizationSchedulerProperties(
		boolean enabled,
		Duration leaseDuration,
		Duration recoveryDelay,
		int claimLimit
) {

	public AttendanceFinalizationSchedulerProperties {
		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			leaseDuration = Duration.ofMinutes(2);
		}
		if (recoveryDelay == null || recoveryDelay.isZero() || recoveryDelay.isNegative()) {
			recoveryDelay = Duration.ofMinutes(1);
		}
		if (claimLimit <= 0) {
			claimLimit = 20;
		}
	}
}
