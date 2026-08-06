package com.example.attend.retention;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

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
		requireEncryptedTransport(url);
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

	/** pgjdbc 자체 parser가 실제 연결에 적용할 최종 sslmode를 검사한다. */
	private static void requireEncryptedTransport(String url) {
		String sslMode = null;
		try {
			Driver driver = DriverManager.getDriver(url);
			for (DriverPropertyInfo property :
					driver.getPropertyInfo(url, new Properties())) {
				if ("sslmode".equalsIgnoreCase(property.name)) {
					sslMode = property.value;
					break;
				}
			}
		} catch (SQLException exception) {
			throw new IllegalStateException(
					"RETENTION_DB_URL could not be parsed safely");
		}
		if (!requiresEncryption(sslMode)) {
			throw new IllegalStateException(
					"RETENTION_DB_URL must require encrypted transport");
		}
	}

	private static boolean requiresEncryption(String sslMode) {
		return "require".equalsIgnoreCase(sslMode)
				|| "verify-ca".equalsIgnoreCase(sslMode)
				|| "verify-full".equalsIgnoreCase(sslMode);
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
