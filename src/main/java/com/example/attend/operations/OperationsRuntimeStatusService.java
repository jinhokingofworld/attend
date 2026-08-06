package com.example.attend.operations;

import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.config.DeviceApiProperties;
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
	private final DatabaseRuntimeStatusSource databaseStatusSource;
	private final BackupRuntimeStatusSource backupStatusSource;
	private final Instant startedAt;
	private final String version;

	/** 시작 시각·빌드 버전과 세 feature flag의 읽기 전용 공급자를 받는다. */
	public OperationsRuntimeStatusService(
			AdminSecurityProperties adminProperties,
			DeviceApiProperties deviceProperties,
			Environment environment,
			DatabaseRuntimeStatusSource databaseStatusSource,
			BackupRuntimeStatusSource backupStatusSource,
			ApplicationContext applicationContext,
			ObjectProvider<BuildProperties> buildPropertiesProvider) {
		this.adminProperties = adminProperties;
		this.deviceProperties = deviceProperties;
		this.environment = environment;
		this.databaseStatusSource = databaseStatusSource;
		this.backupStatusSource = backupStatusSource;
		this.startedAt = Instant.ofEpochMilli(applicationContext.getStartupDate());
		BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
		this.version = buildProperties == null ? "개발 빌드" : buildProperties.getVersion();
	}

	/** 운영 화면에 허용된 제한된 정보만 immutable 응답으로 만든다. */
	public OperationsRuntimeStatus current() {
		return current(databaseStatusSource.current());
	}

	/** 이미 실패한 DB 경로를 재시도하지 않고 비민감 장애 상태를 조립한다. */
	public OperationsRuntimeStatus currentAfterDatabaseFailure() {
		return current("장애 · DB 연결 또는 업무 집계 확인 실패");
	}

	private OperationsRuntimeStatus current(String databaseStatus) {
		return new OperationsRuntimeStatus(
				version,
				startedAt,
				adminProperties.writeEnabled(),
				deviceProperties.enabled(),
				environment.getProperty(
						"attendance.scheduler.enabled", Boolean.class, false),
				databaseStatus,
				backupStatusSource.current());
	}
}
