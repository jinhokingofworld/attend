package com.example.attend.operations;

import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.config.DeviceApiProperties;
import com.example.attend.database.DatabaseMigrationRunner;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 비밀값을 읽거나 반환하지 않고 현재 process의 운영 상태만 조립한다. */
@Service
public final class OperationsRuntimeStatusService {

	private final AdminSecurityProperties adminProperties;
	private final DeviceApiProperties deviceProperties;
	private final Environment environment;
	private final Instant startedAt;
	private final String version;

	/** 시작 시각·빌드 버전과 세 feature flag의 읽기 전용 공급자를 받는다. */
	public OperationsRuntimeStatusService(
			AdminSecurityProperties adminProperties,
			DeviceApiProperties deviceProperties,
			Environment environment,
			ApplicationContext applicationContext,
			ObjectProvider<BuildProperties> buildPropertiesProvider) {
		this.adminProperties = adminProperties;
		this.deviceProperties = deviceProperties;
		this.environment = environment;
		this.startedAt = Instant.ofEpochMilli(applicationContext.getStartupDate());
		BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
		this.version = buildProperties == null ? "개발 빌드" : buildProperties.getVersion();
	}

	/** 운영 화면에 허용된 제한된 정보만 immutable 응답으로 만든다. */
	public OperationsRuntimeStatus current() {
		return new OperationsRuntimeStatus(
				version,
				startedAt,
				adminProperties.writeEnabled(),
				deviceProperties.enabled(),
				environment.getProperty(
						"attendance.scheduler.enabled", Boolean.class, false),
				"연결됨 · Flyway V%s 기동 검증 통과".formatted(
						formattedMigrationTargetVersion()),
				"확인 불가 · 상태 source 미구성");
	}

	/** 현재 정수 target은 세 자리로 표시하고, 향후 dotted version은 그대로 보존한다. */
	private static String formattedMigrationTargetVersion() {
		String version = DatabaseMigrationRunner.TARGET_VERSION.getVersion();
		if (!version.isEmpty()
				&& version.chars().allMatch(Character::isDigit)) {
			return "0".repeat(Math.max(0, 3 - version.length())) + version;
		}
		return version;
	}
}
