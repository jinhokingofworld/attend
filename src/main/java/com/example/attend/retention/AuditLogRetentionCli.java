package com.example.attend.retention;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * 웹 runtime과 분리된 로그 보유기간 작업의 one-shot 진입점이다.
 *
 * <p>이 CLI는 {@code retention_worker} credential로만 실행한다. 호출 가능한
 * DB 함수에는 cutoff나 대상 ID 인자가 없으므로, 이 프로세스는 고정 보유기간이
 * 지난 {@code audit_log}와 {@code tag_event_log} 행 이외에는 삭제 범위를 넓힐 수 없다.</p>
 */
public final class AuditLogRetentionCli {

	private static final String PURGE_AUDIT_LOG_SQL =
			"SELECT public.attend_purge_expired_audit_log_batch()";
	private static final String PURGE_TAG_EVENT_LOG_SQL =
			"SELECT public.attend_purge_expired_tag_event_log_batch()";
	private static final String PURGE_TELEGRAM_WEBHOOK_UPDATE_SQL =
			"SELECT public.attend_purge_expired_telegram_webhook_update_batch()";
	private static final int MAX_BATCH_SIZE = 500;
	private static final int MAX_BATCHES_PER_RUN = 25;
	private static final int QUERY_TIMEOUT_SECONDS = 30;

	private AuditLogRetentionCli() {
	}

	/**
	 * 환경변수의 분리된 worker 연결로 제한 batch를 반복 실행한다.
	 *
	 * <p>각 statement는 auto-commit transaction으로 실행한다. 한 batch 실패가
	 * 이전에 commit된 batch를 되돌리지는 않으며, 다음 container 재시작에서
	 * 남은 batch부터 다시 시도한다.</p>
	 *
	 * @param args 사용하지 않는다. 비밀값은 명령행으로 받지 않는다.
	 */
	public static void main(String[] args) {
		try {
			RunResult result = run(System.getenv(), DriverManager::getConnection);
			System.out.printf(
					"retention=SUCCESS audit_deleted_rows=%d audit_batches=%d "
							+ "tag_event_deleted_rows=%d tag_event_batches=%d "
							+ "telegram_webhook_deleted_rows=%d telegram_webhook_batches=%d "
							+ "catchup_pending=%s%n",
					result.auditDeletedRows(), result.auditBatches(),
					result.tagEventDeletedRows(), result.tagEventBatches(),
					result.telegramWebhookDeletedRows(), result.telegramWebhookBatches(),
					result.catchUpPending());
		} catch (SQLException | RuntimeException exception) {
			// DB URL, 계정, SQL 원문, 행 식별자는 worker log에도 쓰지 않는다.
			System.err.println("retention=FAILURE");
			System.exit(1);
		}
	}

	/**
	 * 하나의 DB 연결에서 최대 25개의 독립 batch를 실행한다.
	 *
	 * @param environment retention worker 환경변수
	 * @param connectionFactory 테스트 가능한 JDBC 연결 공급자
	 * @return 삭제 행 수와 실제 호출 batch 수
	 * @throws SQLException DB 호출에 실패했을 때
	 */
	static RunResult run(
			Map<String, String> environment,
			ConnectionFactory connectionFactory
	) throws SQLException {
		RetentionDatabaseConnection databaseConnection =
				RetentionDatabaseConnection.from(environment);

		try (Connection connection = connectionFactory.open(
				databaseConnection.url(),
				databaseConnection.username(),
				databaseConnection.password())) {
			connection.setAutoCommit(true);
			RetentionDatabasePrivilegeGuard.verify(connection);
			PurgeResult audit = purgeBatches(connection, PURGE_AUDIT_LOG_SQL, "audit");
			PurgeResult tagEvent = purgeBatches(
					connection,
					PURGE_TAG_EVENT_LOG_SQL,
					"tag event");
			PurgeResult telegramWebhook = purgeBatches(
					connection,
					PURGE_TELEGRAM_WEBHOOK_UPDATE_SQL,
					"Telegram webhook update");
			return new RunResult(
					audit.deletedRows(), audit.batches(),
					tagEvent.deletedRows(), tagEvent.batches(),
					telegramWebhook.deletedRows(), telegramWebhook.batches(),
					audit.batches() == MAX_BATCHES_PER_RUN
							|| tagEvent.batches() == MAX_BATCHES_PER_RUN
							|| telegramWebhook.batches() == MAX_BATCHES_PER_RUN);
		}
	}

	private static PurgeResult purgeBatches(
			Connection connection,
			String purgeSql,
			String retentionType) throws SQLException {
		int deletedRows = 0;
		int batches = 0;
		for (int index = 0; index < MAX_BATCHES_PER_RUN; index++) {
			int deleted = purgeOneBatch(connection, purgeSql);
			if (deleted < 0 || deleted > MAX_BATCH_SIZE) {
				throw new IllegalStateException(
						"Invalid " + retentionType + " retention batch result");
			}
			deletedRows += deleted;
			batches++;
			if (deleted < MAX_BATCH_SIZE) {
				break;
			}
		}
		return new PurgeResult(deletedRows, batches);
	}

	private static int purgeOneBatch(
			Connection connection,
			String purgeSql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(purgeSql)) {
			statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					throw new SQLException("Audit retention batch did not return a count");
				}
				return resultSet.getInt(1);
			}
		}
	}

	@FunctionalInterface
	interface ConnectionFactory {
		Connection open(String url, String username, String password)
				throws SQLException;
	}

	/** 제한 작업 한 번의 비민감 요약이다. */
	record RunResult(
			int auditDeletedRows,
			int auditBatches,
			int tagEventDeletedRows,
			int tagEventBatches,
			int telegramWebhookDeletedRows,
			int telegramWebhookBatches,
			boolean catchUpPending) {
	}

	private record PurgeResult(int deletedRows, int batches) {
	}
}
