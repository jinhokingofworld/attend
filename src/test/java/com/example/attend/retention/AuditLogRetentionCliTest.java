package com.example.attend.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** one-shot worker가 제한 batch를 안전하게 반복하는지 검증한다. */
class AuditLogRetentionCliTest {

	/** 첫 full batch 뒤 partial batch가 오면 다음 호출 없이 종료한다. */
	@Test
	void runsIndependentBatchesUntilTheFunctionReturnsAPartialCount()
			throws Exception {
		Connection connection = mock(Connection.class);
		PreparedStatement firstStatement = mock(PreparedStatement.class);
		PreparedStatement secondStatement = mock(PreparedStatement.class);
		PreparedStatement tagEventStatement = mock(PreparedStatement.class);
		ResultSet firstResult = resultSetWithCount(500);
		ResultSet secondResult = resultSetWithCount(7);
		ResultSet tagEventResult = resultSetWithCount(3);
		when(connection.prepareStatement(anyString()))
				.thenReturn(firstStatement, secondStatement, tagEventStatement);
		when(firstStatement.executeQuery()).thenReturn(firstResult);
		when(secondStatement.executeQuery()).thenReturn(secondResult);
		when(tagEventStatement.executeQuery()).thenReturn(tagEventResult);

		AuditLogRetentionCli.RunResult result = AuditLogRetentionCli.run(
				environment(),
				(url, username, password) -> connection);

		assertThat(result.auditDeletedRows()).isEqualTo(507);
		assertThat(result.auditBatches()).isEqualTo(2);
		assertThat(result.tagEventDeletedRows()).isEqualTo(3);
		assertThat(result.tagEventBatches()).isEqualTo(1);
		assertThat(result.catchUpPending()).isFalse();
		verify(connection).setAutoCommit(true);
		verify(firstStatement).setQueryTimeout(30);
		verify(secondStatement).setQueryTimeout(30);
		verify(tagEventStatement).setQueryTimeout(30);
	}

	/** 25개의 full batch는 다음 분 단위 실행에서도 이어서 정리해야 함을 표시한다. */
	@Test
	void marksCatchUpPendingWhenABoundedRunConsumesAllBatches() throws Exception {
		Connection connection = mock(Connection.class);
		PreparedStatement auditStatement = mock(PreparedStatement.class);
		PreparedStatement tagEventStatement = mock(PreparedStatement.class);
		when(connection.prepareStatement(
				"SELECT public.attend_purge_expired_audit_log_batch()"))
				.thenReturn(auditStatement);
		when(connection.prepareStatement(
				"SELECT public.attend_purge_expired_tag_event_log_batch()"))
				.thenReturn(tagEventStatement);
		ResultSet auditResult = resultSetWithCount(500);
		ResultSet tagEventResult = resultSetWithCount(0);
		when(auditStatement.executeQuery()).thenReturn(auditResult);
		when(tagEventStatement.executeQuery()).thenReturn(tagEventResult);

		AuditLogRetentionCli.RunResult result = AuditLogRetentionCli.run(
				environment(),
				(url, username, password) -> connection);

		assertThat(result.auditDeletedRows()).isEqualTo(12_500);
		assertThat(result.auditBatches()).isEqualTo(25);
		assertThat(result.tagEventDeletedRows()).isZero();
		assertThat(result.catchUpPending()).isTrue();
	}

	/** DB 함수가 약속한 500행 상한을 넘기면 다음 삭제를 진행하지 않는다. */
	@Test
	void rejectsAnUnexpectedBatchResult() throws Exception {
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		ResultSet resultSet = resultSetWithCount(501);
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(resultSet);

		assertThatThrownBy(() -> AuditLogRetentionCli.run(
				environment(),
				(url, username, password) -> connection))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Invalid audit retention batch result");
	}

	private static ResultSet resultSetWithCount(int count) throws Exception {
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.next()).thenReturn(true);
		when(resultSet.getInt(1)).thenReturn(count);
		return resultSet;
	}

	private static Map<String, String> environment() {
		return Map.of(
				"RETENTION_DB_URL",
				"jdbc:postgresql://db.example.test/attend?sslmode=require",
				"RETENTION_DB_USERNAME", "retention_worker",
				"RETENTION_DB_PASSWORD", "test-only-secret");
	}
}
