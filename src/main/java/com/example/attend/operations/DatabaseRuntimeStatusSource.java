package com.example.attend.operations;

import com.example.attend.database.SchemaVersionGuard;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** DB 연결과 현재 release가 승인한 Flyway target을 실제로 확인한다. */
@Component
public final class DatabaseRuntimeStatusSource {

	private static final int QUERY_TIMEOUT_SECONDS = 3;

	private final DataSource dataSource;

	/** 애플리케이션 runtime 권한의 데이터소스를 검사 대상으로 사용한다. */
	public DatabaseRuntimeStatusSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * 연결용 경량 query와 schema history exact-match 검사를 실행한다.
	 *
	 * <p>DB URL, 계정, SQL 예외와 schema 상세는 화면 문구에 포함하지 않는다.</p>
	 *
	 * @return 비밀값이 없는 제한된 상태 문구
	 */
	public String current() {
		if (!isReachable()) {
			return "장애 · DB 연결 확인 실패";
		}
		try {
			SchemaVersionGuard.verify(dataSource);
			return "정상 · 연결 및 승인된 Flyway target 일치";
		} catch (RuntimeException exception) {
			return "경고 · 연결됨, 승인된 Flyway target 확인 실패";
		}
	}

	private boolean isReachable() {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
			try (ResultSet resultSet = statement.executeQuery("SELECT 1")) {
				return resultSet.next() && resultSet.getInt(1) == 1;
			}
		} catch (SQLException | RuntimeException exception) {
			return false;
		}
	}
}
