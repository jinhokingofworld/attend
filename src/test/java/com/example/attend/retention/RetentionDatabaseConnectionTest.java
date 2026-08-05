package com.example.attend.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** retention worker JDBC 설정이 URL에 포함된 비밀값을 거부하는지 검증한다. */
class RetentionDatabaseConnectionTest {

	/** URL authority에 credential을 넣으면 실제 값을 오류에 담지 않고 거부한다. */
	@Test
	void rejectsEmbeddedCredentialsWithoutEchoingTheUrl() {
		String secret = "do-not-print-this-retention-secret";
		Map<String, String> environment = Map.of(
				"RETENTION_DB_URL",
				"jdbc:postgresql://retention:" + secret + "@db.example.test/app",
				"RETENTION_DB_USERNAME", "retention_worker",
				"RETENTION_DB_PASSWORD", secret);

		assertThatThrownBy(() -> RetentionDatabaseConnection.from(environment))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("RETENTION_DB_URL must not embed a username or password")
				.hasMessageNotContaining(secret)
				.hasMessageNotContaining("db.example.test");
	}

	/** pgjdbc URL properties must not override the worker's separate credential. */
	@Test
	void rejectsCredentialQueryParametersWithoutEchoingTheUrl() {
		String secret = "do-not-print-query-retention-secret";
		Map<String, String> environment = Map.of(
				"RETENTION_DB_URL",
				"jdbc:postgresql://db.example.test/app?sslmode=require&"
						+ "u%73er=retention_worker&password=" + secret,
				"RETENTION_DB_USERNAME", "retention_worker",
				"RETENTION_DB_PASSWORD", secret);

		assertThatThrownBy(() -> RetentionDatabaseConnection.from(environment))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("RETENTION_DB_URL must not include a username or password")
				.hasMessageNotContaining(secret)
				.hasMessageNotContaining("db.example.test");
	}

	/** 분리된 direct JDBC 설정은 원문을 바꾸지 않고 worker에게 전달한다. */
	@Test
	void acceptsSeparatedConnectionSettings() {
		RetentionDatabaseConnection connection =
				RetentionDatabaseConnection.from(Map.of(
						"RETENTION_DB_URL",
						"jdbc:postgresql://db.example.test/app?sslmode=require",
						"RETENTION_DB_USERNAME", "retention_worker",
						"RETENTION_DB_PASSWORD", "separate-secret"));

		assertThat(connection.url()).isEqualTo(
				"jdbc:postgresql://db.example.test/app?sslmode=require");
		assertThat(connection.username()).isEqualTo("retention_worker");
		assertThat(connection.password()).isEqualTo("separate-secret");
	}
}
