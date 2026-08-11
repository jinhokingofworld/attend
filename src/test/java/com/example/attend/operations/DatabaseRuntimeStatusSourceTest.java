package com.example.attend.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 운영 화면의 DB 상태가 실제 연결·schema 검사 결과를 반영하는지 검증한다. */
class DatabaseRuntimeStatusSourceTest {

	/** 경량 query와 V001~V015 exact match가 모두 성공해야 정상이다. */
	@Test
	void reportsHealthyOnlyAfterConnectionAndExactSchemaVerification()
			throws SQLException {
		Statement schemaStatement = mock(Statement.class);
		DataSource dataSource = dataSourceWithVersions(
				schemaStatement,
				"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");

		assertThat(new DatabaseRuntimeStatusSource(dataSource).current())
				.isEqualTo("정상 · 연결 및 승인된 Flyway target 일치");
		verify(schemaStatement).setQueryTimeout(3);
	}

	/** 연결은 되더라도 누락 migration이 있으면 target 일치로 표시하지 않는다. */
	@Test
	void reportsSchemaVerificationFailureWithoutDatabaseDetails()
			throws SQLException {
		DataSource dataSource = dataSourceWithVersions(
				mock(Statement.class),
				"1", "2", "3", "4", "5", "6", "7", "8");

		String status = new DatabaseRuntimeStatusSource(dataSource).current();

		assertThat(status)
				.isEqualTo("경고 · 연결됨, 승인된 Flyway target 확인 실패")
				.doesNotContain("jdbc:", "flyway_schema_history", "SQLException");
	}

	/** DB 예외 원문은 관리자 HTML용 상태에 포함하지 않는다. */
	@Test
	void reportsConnectionFailureWithoutLeakingExceptionMessage()
			throws SQLException {
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenThrow(
				new SQLException("jdbc:postgresql://user:password@secret-host/db"));

		String status = new DatabaseRuntimeStatusSource(dataSource).current();

		assertThat(status)
				.isEqualTo("장애 · DB 연결 확인 실패")
				.doesNotContain("password", "secret-host", "jdbc:");
	}

	private static DataSource dataSourceWithVersions(
			Statement schemaStatement,
			String... versions)
			throws SQLException {
		DataSource dataSource = mock(DataSource.class);
		Connection healthConnection = mock(Connection.class);
		Statement healthStatement = mock(Statement.class);
		ResultSet healthResult = mock(ResultSet.class);
		Connection schemaConnection = mock(Connection.class);
		ResultSet schemaResult = mock(ResultSet.class);

		when(dataSource.getConnection())
				.thenReturn(healthConnection, schemaConnection);
		when(healthConnection.createStatement()).thenReturn(healthStatement);
		when(healthStatement.executeQuery("SELECT 1")).thenReturn(healthResult);
		when(healthResult.next()).thenReturn(true);
		when(healthResult.getInt(1)).thenReturn(1);

		when(schemaConnection.createStatement()).thenReturn(schemaStatement);
		when(schemaStatement.executeQuery(anyString())).thenReturn(schemaResult);
		Boolean[] rows = new Boolean[versions.length + 1];
		for (int index = 0; index < versions.length; index++) {
			rows[index] = true;
		}
		rows[versions.length] = false;
		when(schemaResult.next()).thenReturn(
				rows[0], java.util.Arrays.copyOfRange(rows, 1, rows.length));
		when(schemaResult.getBoolean("success")).thenReturn(true);
		when(schemaResult.getString("version")).thenReturn(
				versions[0], java.util.Arrays.copyOfRange(
						versions, 1, versions.length));
		when(schemaResult.getString("type")).thenReturn("SQL");
		return dataSource;
	}
}
