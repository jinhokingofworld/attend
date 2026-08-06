package com.example.attend.operations;

import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.config.DeviceApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 운영 화면의 schema 상태가 실행기의 실제 목표 버전을 따라가는지 검증한다. */
class OperationsRuntimeStatusServiceTest {

	@Test
	void exposesDatabaseAndBackupStatusSources() {
		ApplicationContext applicationContext = mock(ApplicationContext.class);
		when(applicationContext.getStartupDate()).thenReturn(1_700_000_000_000L);
		@SuppressWarnings("unchecked")
		ObjectProvider<org.springframework.boot.info.BuildProperties>
				buildPropertiesProvider = mock(ObjectProvider.class);
		when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
		DatabaseRuntimeStatusSource databaseStatusSource =
				mock(DatabaseRuntimeStatusSource.class);
		when(databaseStatusSource.current()).thenReturn(
				"정상 · 연결 및 승인된 Flyway target 일치");
		BackupRuntimeStatusSource backupStatusSource =
				mock(BackupRuntimeStatusSource.class);
		BackupRuntimeStatus backupStatus = new BackupRuntimeStatus(
				BackupRuntimeStatus.State.NOT_CONFIGURED,
				null, null, null, null);
		when(backupStatusSource.current()).thenReturn(backupStatus);

		OperationsRuntimeStatus status = new OperationsRuntimeStatusService(
				new AdminSecurityProperties(true, null, null),
				new DeviceApiProperties(false, null),
				new MockEnvironment(),
				databaseStatusSource,
				backupStatusSource,
				applicationContext,
				buildPropertiesProvider
		).current();

		assertThat(status.databaseStatus())
				.isEqualTo("정상 · 연결 및 승인된 Flyway target 일치");
		assertThat(status.backupStatus()).isSameAs(backupStatus);
	}
}
