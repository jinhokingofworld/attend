package com.example.attend.operations;

import com.example.attend.operations.BackupRuntimeStatus.State;
import com.example.attend.operations.BackupRuntimeStatus.StorageType;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 웹 process 밖에서 수행한 백업의 비민감 상태 계약을 읽고 검증한다.
 *
 * <p>{@code attendance.operations.backup.status-file}이 설정되면 그 파일만
 * source로 사용한다. 파일은 원자적 rename으로 교체하며 아래 Java properties
 * 형식을 사용한다.</p>
 *
 * <pre>
 * version=1
 * result=SUCCESS
 * observed-at=2026-08-05T01:00:00Z
 * last-success-at=2026-08-05T01:00:00Z
 * storage-type=OBJECT_STORAGE
 * last-restore-test-at=2026-07-01T02:00:00Z
 * </pre>
 *
 * <p>파일을 설정하지 않은 환경은 같은 이름의 Spring property로 메타데이터를
 * 주입할 수 있다. 파일 경로, checksum, credential과 자유 형식 메시지는 계약에
 * 없으며 읽더라도 결과 객체나 화면으로 전달하지 않는다.</p>
 */
@Component
public final class BackupRuntimeStatusSource {

	private static final String PREFIX = "attendance.operations.backup.";
	private static final String STATUS_FILE_PROPERTY = PREFIX + "status-file";
	private static final String MAX_AGE_PROPERTY = PREFIX + "max-age";
	private static final Duration DEFAULT_MAX_AGE = Duration.ofHours(24);
	private static final Duration MAX_ALLOWED_AGE = Duration.ofHours(24);
	private static final Duration FUTURE_CLOCK_TOLERANCE = Duration.ofMinutes(5);
	private static final long MAX_STATUS_FILE_BYTES = 16 * 1024;

	private final Environment environment;
	private final Clock clock;

	/** Spring 환경과 업무 공통 시계를 사용한다. */
	public BackupRuntimeStatusSource(Environment environment, Clock clock) {
		this.environment = environment;
		this.clock = clock;
	}

	/**
	 * 현재 source를 매번 다시 읽어 외부 job의 갱신을 재기동 없이 반영한다.
	 *
	 * @return 검증된 제한 메타데이터 또는 보수적인 확인 불가 상태
	 */
	public BackupRuntimeStatus current() {
		try {
			String statusFile = trimmed(
					environment.getProperty(STATUS_FILE_PROPERTY));
			RawStatus rawStatus;
			if (statusFile != null) {
				rawStatus = readFile(Path.of(statusFile));
			} else {
				rawStatus = readEnvironment();
				if (rawStatus == null) {
					return BackupRuntimeStatus.notConfigured();
				}
			}
			return validate(rawStatus, maxAge(), Instant.now(clock));
		} catch (IOException | RuntimeException exception) {
			return BackupRuntimeStatus.unavailable();
		}
	}

	private RawStatus readFile(Path path) throws IOException {
		if (!Files.isRegularFile(path)
				|| Files.size(path) > MAX_STATUS_FILE_BYTES) {
			throw new IOException("Backup status source is unavailable");
		}

		Properties properties = new Properties();
		try (BufferedReader reader = Files.newBufferedReader(
				path, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		if (!"1".equals(trimmed(properties.getProperty("version")))) {
			throw new IllegalArgumentException("Unsupported backup status version");
		}
		return new RawStatus(
				properties.getProperty("result"),
				properties.getProperty("observed-at"),
				properties.getProperty("last-success-at"),
				properties.getProperty("storage-type"),
				properties.getProperty("last-restore-test-at"));
	}

	private RawStatus readEnvironment() {
		String result = environment.getProperty(PREFIX + "result");
		String observedAt = environment.getProperty(PREFIX + "observed-at");
		String lastSuccessAt = environment.getProperty(PREFIX + "last-success-at");
		String storageType = environment.getProperty(PREFIX + "storage-type");
		String restoreTestAt = environment.getProperty(
				PREFIX + "last-restore-test-at");
		if (trimmed(result) == null
				&& trimmed(observedAt) == null
				&& trimmed(lastSuccessAt) == null
				&& trimmed(storageType) == null
				&& trimmed(restoreTestAt) == null) {
			return null;
		}
		return new RawStatus(
				result, observedAt, lastSuccessAt, storageType, restoreTestAt);
	}

	private Duration maxAge() {
		String configured = trimmed(environment.getProperty(MAX_AGE_PROPERTY));
		Duration maxAge = configured == null
				? DEFAULT_MAX_AGE : Duration.parse(configured);
		if (maxAge.isZero() || maxAge.isNegative()
				|| maxAge.compareTo(MAX_ALLOWED_AGE) > 0) {
			throw new IllegalArgumentException(
					"Backup status max age must be within 24 hours");
		}
		return maxAge;
	}

	private static BackupRuntimeStatus validate(
			RawStatus raw, Duration maxAge, Instant now) {
		Result result = Result.valueOf(required(raw.result()));
		Instant observedAt = parseRequiredInstant(raw.observedAt());
		Instant lastSuccessAt = parseOptionalInstant(raw.lastSuccessAt());
		Instant lastRestoreTestAt = parseOptionalInstant(raw.lastRestoreTestAt());
		StorageType storageType = parseOptionalStorageType(raw.storageType());

		Instant latestAccepted = now.plus(FUTURE_CLOCK_TOLERANCE);
		if (observedAt.isAfter(latestAccepted)
				|| isAfter(lastSuccessAt, observedAt)
				|| isAfter(lastRestoreTestAt, observedAt)) {
			throw new IllegalArgumentException("Invalid backup status timestamp");
		}
		if (result == Result.SUCCESS
				&& (lastSuccessAt == null || storageType == null)) {
			throw new IllegalArgumentException(
					"Successful backup status requires success metadata");
		}
		if (lastSuccessAt != null && storageType == null) {
			throw new IllegalArgumentException(
					"Backup storage type is required with a success timestamp");
		}

		Instant freshnessBoundary = now.minus(maxAge);
		if (observedAt.isBefore(freshnessBoundary)
				|| result == Result.SUCCESS
				&& lastSuccessAt.isBefore(freshnessBoundary)) {
			return new BackupRuntimeStatus(
					State.STALE,
					observedAt,
					lastSuccessAt,
					storageType,
					lastRestoreTestAt);
		}

		return new BackupRuntimeStatus(
				result == Result.SUCCESS ? State.SUCCESS : State.FAILURE,
				observedAt,
				lastSuccessAt,
				storageType,
				lastRestoreTestAt);
	}

	private static Instant parseRequiredInstant(String value) {
		String required = required(value);
		try {
			return Instant.parse(required);
		} catch (DateTimeParseException exception) {
			throw new IllegalArgumentException("Invalid backup status timestamp");
		}
	}

	private static Instant parseOptionalInstant(String value) {
		String normalized = trimmed(value);
		return normalized == null ? null : parseRequiredInstant(normalized);
	}

	private static StorageType parseOptionalStorageType(String value) {
		String normalized = trimmed(value);
		return normalized == null ? null : StorageType.valueOf(normalized);
	}

	private static boolean isAfter(Instant value, Instant upperBound) {
		return value != null && value.isAfter(upperBound);
	}

	private static String required(String value) {
		String normalized = trimmed(value);
		if (normalized == null) {
			throw new IllegalArgumentException("Required backup status value missing");
		}
		return normalized;
	}

	private static String trimmed(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private enum Result {
		SUCCESS,
		FAILURE
	}

	private record RawStatus(
			String result,
			String observedAt,
			String lastSuccessAt,
			String storageType,
			String lastRestoreTestAt) {
	}
}
