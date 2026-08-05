package com.example.attend.operations;

import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.config.DeviceApiProperties;
import com.example.attend.database.DatabaseMigrationRunner;
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
	void exposesTheMigrationRunnerTargetVersion() {
		ApplicationContext applicationContext = mock(ApplicationContext.class);
		when(applicationContext.getStartupDate()).thenReturn(1_700_000_000_000L);
		@SuppressWarnings("unchecked")
		ObjectProvider<org.springframework.boot.info.BuildProperties>
				buildPropertiesProvider = mock(ObjectProvider.class);
		when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);

		OperationsRuntimeStatus status = new OperationsRuntimeStatusService(
				new AdminSecurityProperties(true, null, null),
				new DeviceApiProperties(false, null),
				new MockEnvironment(),
				applicationContext,
				buildPropertiesProvider
		).current();

		String targetVersion = DatabaseMigrationRunner.TARGET_VERSION.getVersion();
		String displayedTargetVersion = !targetVersion.isEmpty()
				&& targetVersion.chars().allMatch(Character::isDigit)
				? "0".repeat(Math.max(0, 3 - targetVersion.length())) + targetVersion
				: targetVersion;
		assertThat(status.databaseStatus()).contains("V" + displayedTargetVersion);
	}
}
