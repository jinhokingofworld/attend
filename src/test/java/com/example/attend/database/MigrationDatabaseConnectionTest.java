package com.example.attend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** migration 연결 설정이 URL을 통해 자격증명을 노출하지 않는지 검증한다. */
class MigrationDatabaseConnectionTest {

	/** URL에 내장된 자격증명은 실제 값을 오류에 포함하지 않고 거부한다. */
	@Test
	void rejectsEmbeddedCredentialsWithoutEchoingTheUrl() {
		String secret = "do-not-print-this-secret";
		Map<String, String> environment = Map.of(
				"FLYWAY_DB_URL",
				"jdbc:postgresql://owner:" + secret + "@db.example.test/app",
				"FLYWAY_DB_USERNAME", "owner",
				"FLYWAY_DB_PASSWORD", secret);

		assertThatThrownBy(() -> MigrationDatabaseConnection.from(environment))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("FLYWAY_DB_URL must not embed a username or password")
				.hasMessageNotContaining(secret)
				.hasMessageNotContaining("db.example.test");
	}

	/** URL과 자격증명을 분리한 설정은 원문 변경 없이 사용한다. */
	@Test
	void acceptsSeparatedConnectionSettings() {
		MigrationDatabaseConnection connection =
				MigrationDatabaseConnection.from(Map.of(
						"FLYWAY_DB_URL",
						"jdbc:postgresql://db.example.test/app?sslmode=require",
						"FLYWAY_DB_USERNAME", "migration_owner",
						"FLYWAY_DB_PASSWORD", "separate-secret"));

		assertThat(connection.url()).isEqualTo(
				"jdbc:postgresql://db.example.test/app?sslmode=require");
		assertThat(connection.username()).isEqualTo("migration_owner");
		assertThat(connection.password()).isEqualTo("separate-secret");
	}
}
