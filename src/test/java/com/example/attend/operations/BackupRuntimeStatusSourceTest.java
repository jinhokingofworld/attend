package com.example.attend.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.attend.operations.BackupRuntimeStatus.State;
import com.example.attend.operations.BackupRuntimeStatus.StorageType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;

/** 외부 백업 상태 계약의 성공·실패·오래됨·오염 경계를 검증한다. */
class BackupRuntimeStatusSourceTest {

	private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@TempDir
	Path temporaryDirectory;

	/** source가 없으면 백업 성공을 추측하지 않는다. */
	@Test
	void reportsNotConfiguredWithoutAStatusSource() {
		BackupRuntimeStatus status = source(new MockEnvironment()).current();

		assertThat(status.state()).isEqualTo(State.NOT_CONFIGURED);
		assertThat(status.lastSuccessAt()).isNull();
	}

	/** version 1 파일의 허용된 메타데이터만 성공 상태로 전달한다. */
	@Test
	void readsFreshSuccessfulStatusFileWithoutExposingRawValues()
			throws IOException {
		Path statusFile = writeStatus("""
				version=1
				result=SUCCESS
				observed-at=2026-08-05T11:59:00Z
				last-success-at=2026-08-05T11:58:00Z
				storage-type=OBJECT_STORAGE
				last-restore-test-at=2026-08-01T01:00:00Z
				ignored-secret=do-not-expose-this
				""");
		MockEnvironment environment = new MockEnvironment()
				.withProperty("attendance.operations.backup.status-file",
						statusFile.toString())
				.withProperty("attendance.operations.backup.max-age", "PT24H");

		BackupRuntimeStatus status = source(environment).current();

		assertThat(status.state()).isEqualTo(State.SUCCESS);
		assertThat(status.lastSuccessAt())
				.isEqualTo(Instant.parse("2026-08-05T11:58:00Z"));
		assertThat(status.storageType()).isEqualTo(StorageType.OBJECT_STORAGE);
		assertThat(status.toString())
				.doesNotContain("do-not-expose-this", statusFile.toString());
	}

	/** 최근 성공이어도 source 자체가 오래됐으면 기한 초과로 낮춘다. */
	@Test
	void marksOldSourceAsStale() throws IOException {
		Path statusFile = writeStatus("""
				version=1
				result=SUCCESS
				observed-at=2026-08-04T11:59:59Z
				last-success-at=2026-08-04T11:59:59Z
				storage-type=OFF_HOST_FILESYSTEM
				""");
		MockEnvironment environment = new MockEnvironment()
				.withProperty("attendance.operations.backup.status-file",
						statusFile.toString());

		assertThat(source(environment).current().state()).isEqualTo(State.STALE);
	}

	/** 파일 source 오류를 과거 환경변수의 성공값으로 덮어쓰지 않는다. */
	@Test
	void doesNotFallbackToEnvironmentWhenConfiguredFileIsMissing() {
		MockEnvironment environment = successfulEnvironment()
				.withProperty("attendance.operations.backup.status-file",
						temporaryDirectory.resolve("missing.properties").toString());

		assertThat(source(environment).current().state())
				.isEqualTo(State.UNAVAILABLE);
	}

	/** 환경 메타데이터도 같은 검증을 거쳐 최신 작업 실패를 표시할 수 있다. */
	@Test
	void readsValidatedFailureFromEnvironment() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("attendance.operations.backup.result", "FAILURE")
				.withProperty("attendance.operations.backup.observed-at",
						"2026-08-05T11:55:00Z")
				.withProperty("attendance.operations.backup.last-success-at",
						"2026-08-04T12:30:00Z")
				.withProperty("attendance.operations.backup.storage-type",
						"MANAGED_DATABASE_BACKUP");

		BackupRuntimeStatus status = source(environment).current();

		assertThat(status.state()).isEqualTo(State.FAILURE);
		assertThat(status.lastSuccessAt())
				.isEqualTo(Instant.parse("2026-08-04T12:30:00Z"));
	}

	/** application.properties의 운영 환경변수 이름도 실제 계약 값으로 연결된다. */
	@Test
	void mapsDocumentedEnvironmentVariablesThroughApplicationProperties()
			throws IOException {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("BACKUP_STATUS_RESULT", "SUCCESS");
		environment.setProperty(
				"BACKUP_STATUS_OBSERVED_AT", "2026-08-05T11:59:00Z");
		environment.setProperty(
				"BACKUP_LAST_SUCCESS_AT", "2026-08-05T11:58:00Z");
		environment.setProperty("BACKUP_STORAGE_TYPE", "OBJECT_STORAGE");
		environment.getPropertySources().addLast(
				new ResourcePropertySource("classpath:application.properties"));

		BackupRuntimeStatus status = source(environment).current();

		assertThat(status.state()).isEqualTo(State.SUCCESS);
		assertThat(status.storageType()).isEqualTo(StorageType.OBJECT_STORAGE);
	}

	/** 자유 형식 저장소 값과 24시간을 넘는 판정 기준은 확인 불가로 처리한다. */
	@Test
	void rejectsUnboundedMetadataAndPolicyWeakening() {
		MockEnvironment invalidStorage = successfulEnvironment()
				.withProperty("attendance.operations.backup.storage-type",
						"/secret/backup/path");
		MockEnvironment excessiveAge = successfulEnvironment()
				.withProperty("attendance.operations.backup.max-age", "PT25H");

		assertThat(source(invalidStorage).current().state())
				.isEqualTo(State.UNAVAILABLE);
		assertThat(source(excessiveAge).current().state())
				.isEqualTo(State.UNAVAILABLE);
	}

	private Path writeStatus(String content) throws IOException {
		Path statusFile = temporaryDirectory.resolve("backup-status.properties");
		Files.writeString(statusFile, content);
		return statusFile;
	}

	private static MockEnvironment successfulEnvironment() {
		return new MockEnvironment()
				.withProperty("attendance.operations.backup.result", "SUCCESS")
				.withProperty("attendance.operations.backup.observed-at",
						"2026-08-05T11:59:00Z")
				.withProperty("attendance.operations.backup.last-success-at",
						"2026-08-05T11:58:00Z")
				.withProperty("attendance.operations.backup.storage-type",
						"OBJECT_STORAGE");
	}

	private static BackupRuntimeStatusSource source(MockEnvironment environment) {
		return new BackupRuntimeStatusSource(environment, CLOCK);
	}
}
