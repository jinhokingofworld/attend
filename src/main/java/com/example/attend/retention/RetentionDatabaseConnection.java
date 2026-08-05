package com.example.attend.retention;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** retention worker가 사용하는 자격증명 분리 JDBC 연결 설정이다. */
record RetentionDatabaseConnection(String url, String username, String password) {

	/** 환경변수에서 worker 전용 PostgreSQL 연결을 읽고 URL을 검증한다. */
	static RetentionDatabaseConnection from(Map<String, String> environment) {
		String url = required(environment, "RETENTION_DB_URL");
		if (!url.startsWith("jdbc:postgresql://")) {
			throw new IllegalStateException(
					"RETENTION_DB_URL must be a PostgreSQL JDBC URL");
		}
		int authorityStart = "jdbc:postgresql://".length();
		int authorityEnd = url.indexOf('/', authorityStart);
		if (authorityEnd < 0) {
			throw new IllegalStateException(
					"RETENTION_DB_URL must include a database name");
		}
		if (url.substring(authorityStart, authorityEnd).contains("@")) {
			throw new IllegalStateException(
					"RETENTION_DB_URL must not embed a username or password");
		}
		if (hasCredentialQueryParameter(url)) {
			throw new IllegalStateException(
					"RETENTION_DB_URL must not include a username or password");
		}
		return new RetentionDatabaseConnection(
				url,
				required(environment, "RETENTION_DB_USERNAME"),
				required(environment, "RETENTION_DB_PASSWORD"));
	}

	/** JDBC URL query properties must not override the dedicated environment credentials. */
	private static boolean hasCredentialQueryParameter(String url) {
		int queryStart = url.indexOf('?');
		if (queryStart < 0 || queryStart == url.length() - 1) {
			return false;
		}
		for (String parameter : url.substring(queryStart + 1).split("&")) {
			int separator = parameter.indexOf('=');
			String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
			String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
			if ("user".equalsIgnoreCase(name)
					|| "username".equalsIgnoreCase(name)
					|| "password".equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	private static String required(Map<String, String> environment, String name) {
		String value = environment.get(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
					name + " must be supplied outside the application artifact");
		}
		return value;
	}
}
