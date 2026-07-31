package com.example.attend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;

/** 자동 마감 feature flag가 환경값을 실제 Spring 설정으로 전달하는지 검증한다. */
class AttendanceSchedulerConfigurationTest {

	/** 환경변수에 대응하는 값이 true이면 scheduler 설정도 활성화되어야 한다. */
	@Test
	void enablesSchedulerFromEnvironmentSetting() throws IOException {
		MockEnvironment environment = applicationEnvironment();
		environment.setProperty("ATTENDANCE_SCHEDULER_ENABLED", "true");

		assertThat(environment.getProperty(
				"attendance.scheduler.enabled", Boolean.class)).isTrue();
	}

	/** 운영자가 값을 명시하지 않으면 안전한 기본값 false를 유지한다. */
	@Test
	void keepsSchedulerDisabledByDefault() throws IOException {
		MockEnvironment environment = applicationEnvironment();

		assertThat(environment.getProperty(
				"attendance.scheduler.enabled", Boolean.class)).isFalse();
	}

	private static MockEnvironment applicationEnvironment() throws IOException {
		MockEnvironment environment = new MockEnvironment();
		environment.getPropertySources().addLast(
				new ResourcePropertySource("classpath:application.properties"));
		return environment;
	}
}
