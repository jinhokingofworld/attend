package com.example.attend.database;

import java.util.Map;

/**
 * migration CLI가 사용하는 분리된 JDBC URL·계정·비밀번호 설정이다.
 *
 * <p>URL authority에 자격증명을 넣으면 JDBC parse 오류와 예외 로그를 통해 비밀번호가
 * 노출될 수 있으므로, URL에는 host와 database만 허용한다.</p>
 *
 * @param url 자격증명이 포함되지 않은 PostgreSQL JDBC URL
 * @param username migration 전용 계정명
 * @param password migration 전용 비밀번호
 */
record MigrationDatabaseConnection(String url, String username, String password) {

	/**
	 * 환경변수에서 분리된 migration 연결정보를 읽고 URL을 검증한다.
	 *
	 * @param environment 프로세스 환경변수
	 * @return 검증된 연결정보
	 */
	static MigrationDatabaseConnection from(Map<String, String> environment) {
		String url = required(environment, "FLYWAY_DB_URL");
		if (!url.startsWith("jdbc:postgresql://")) {
			throw new IllegalStateException(
					"FLYWAY_DB_URL must be a PostgreSQL JDBC URL");
		}
		int authorityStart = "jdbc:postgresql://".length();
		int authorityEnd = url.indexOf('/', authorityStart);
		if (authorityEnd < 0) {
			throw new IllegalStateException(
					"FLYWAY_DB_URL must include a database name");
		}
		if (url.substring(authorityStart, authorityEnd).contains("@")) {
			throw new IllegalStateException(
					"FLYWAY_DB_URL must not embed a username or password");
		}
		return new MigrationDatabaseConnection(
				url,
				required(environment, "FLYWAY_DB_USERNAME"),
				required(environment, "FLYWAY_DB_PASSWORD"));
	}

	/** 필수 설정이 없으면 값 자체를 출력하지 않고 중단한다. */
	private static String required(Map<String, String> environment, String name) {
		String value = environment.get(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
					name + " must be supplied outside the application artifact");
		}
		return value;
	}
}
