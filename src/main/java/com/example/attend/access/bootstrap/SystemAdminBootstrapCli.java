package com.example.attend.access.bootstrap;

import com.example.attend.access.application.PasswordPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;

/**
 * fresh DB에 최초 {@code SYSTEM_ADMIN} 한 명만 만드는 대화형 CLI다.
 *
 * <p>사용자명과 비밀번호를 명령행 인자나 환경변수로 받지 않는다. 비밀번호가
 * 보이지 않는 실제 terminal console에서만 실행되며, 계정 행이 하나라도 있으면
 * 재실행을 거부한다.</p>
 */
public final class SystemAdminBootstrapCli {

	private SystemAdminBootstrapCli() {
	}

	/**
	 * 제한 DB 계정으로 연결해 한 트랜잭션에서 최초 관리자를 만든다.
	 *
	 * @param args 사용하지 않는다
	 * @throws Exception DB 연결 또는 입력 처리가 실패한 경우
	 */
	public static void main(String[] args) throws Exception {
		Console console = System.console();
		if (console == null) {
			throw new IllegalStateException(
					"bootstrap requires an interactive terminal console");
		}
		Map<String, String> environment = System.getenv();
		String url = required(environment, "FLYWAY_DB_URL");
		String databaseUsername = required(
				environment, "FLYWAY_DB_USERNAME");
		String databasePassword = required(
				environment, "FLYWAY_DB_PASSWORD");

		String username = console.readLine("최초 SYSTEM_ADMIN 사용자명: ");
		char[] password = console.readPassword("비밀번호: ");
		char[] confirmation = console.readPassword("비밀번호 확인: ");
		try {
			String passwordValue = new String(password);
			PasswordPolicy.validate(
					passwordValue,
					new String(confirmation));
			bootstrap(
					url,
					databaseUsername,
					databasePassword,
					normalizeUsername(username),
					new BCryptPasswordEncoder(12).encode(passwordValue));
		} finally {
			Arrays.fill(password, '\0');
			Arrays.fill(confirmation, '\0');
		}
		console.writer().println(
				"최초 SYSTEM_ADMIN 계정을 생성했습니다. 이 명령은 다시 실행할 수 없습니다.");
	}

	private static void bootstrap(
			String url,
			String databaseUsername,
			String databasePassword,
			String username,
			String passwordHash) throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				url, databaseUsername, databasePassword)) {
			connection.setAutoCommit(false);
			try {
				requireFreshAccountTable(connection);
				long accountId = insertAccount(
						connection, username, passwordHash);
				insertAudit(connection, accountId);
				connection.commit();
			} catch (RuntimeException | SQLException exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	private static void requireFreshAccountTable(Connection connection)
			throws SQLException {
		try (PreparedStatement lock = connection.prepareStatement(
				"LOCK TABLE public.account IN SHARE ROW EXCLUSIVE MODE")) {
			lock.execute();
		}
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT count(*) FROM public.account");
			 ResultSet result = statement.executeQuery()) {
			result.next();
			if (result.getLong(1) != 0) {
				throw new IllegalStateException(
						"bootstrap is closed because an account already exists");
			}
		}
	}

	private static long insertAccount(
			Connection connection,
			String username,
			String passwordHash) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO public.account(
				    username,
				    password_hash,
				    system_role,
				    status,
				    password_changed_at)
				VALUES (?, ?, 'SYSTEM_ADMIN', 'ACTIVE', ?)
				RETURNING id
				""")) {
			statement.setString(1, username);
			statement.setString(2, passwordHash);
			statement.setObject(3, OffsetDateTime.now());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new IllegalStateException(
							"bootstrap account was not inserted");
				}
				return result.getLong(1);
			}
		}
	}

	private static void insertAudit(
			Connection connection,
			long accountId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO public.audit_log(
				    actor_type,
				    actor_account_id,
				    action,
				    target_type,
				    target_id,
				    after_data)
				VALUES (
				    'ACCOUNT',
				    ?,
				    'SYSTEM_ADMIN_BOOTSTRAPPED',
				    'ACCOUNT',
				    ?,
				    '{"systemRole":"SYSTEM_ADMIN","status":"ACTIVE"}'::jsonb)
				""")) {
			statement.setLong(1, accountId);
			statement.setString(2, Long.toString(accountId));
			statement.executeUpdate();
		}
	}

	private static String normalizeUsername(String username) {
		if (username == null || username.isBlank()) {
			throw new IllegalArgumentException("username must not be blank");
		}
		username = username.trim();
		if (username.codePointCount(0, username.length()) > 100) {
			throw new IllegalArgumentException(
					"username must not exceed 100 characters");
		}
		return username;
	}

	private static String required(
			Map<String, String> environment,
			String name) {
		String value = environment.get(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required");
		}
		return value;
	}
}
