package com.example.attend.database;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 운영 DB를 변경하지 않고 guarded migration의 입력 조건만 확인하는 명령행 도구다.
 *
 * <p>{@link DatabasePreflightInspector}가 PostgreSQL read-only transaction을 사용하므로
 * 이 명령은 Flyway history, table과 데이터를 만들거나 수정하지 않는다. 연결 URL과
 * 계정명도 출력하지 않고 판정 상태와 일반 사유만 출력한다.</p>
 */
public final class DatabasePreflightCli {

	/** 명령행 진입점만 제공하므로 인스턴스 생성을 막는다. */
	private DatabasePreflightCli() {
	}

	/**
	 * migration 전용 연결정보로 읽기 전용 사전검사를 실행한다.
	 *
	 * @param args 사용하지 않는다. 접속정보는 환경변수로만 받는다.
	 */
	public static void main(String[] args) {
		MigrationDatabaseConnection connection =
				MigrationDatabaseConnection.from(System.getenv());
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				connection.url(), connection.username(), connection.password());

		DatabasePreflightInspector.PreflightResult result =
				new DatabasePreflightInspector().inspect(dataSource);
		System.out.println("Database preflight status: " + result.status());
		System.out.println("Database preflight reason: " + result.reason());
	}
}
