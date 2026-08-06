package com.example.attend.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실제 Bash entry point와 웹 애플리케이션의 상태 파일 계약을 검증한다. */
class BackupScriptIntegrationTest {

	@TempDir
	Path temporaryDirectory;

	/** 성공은 제한된 메타데이터를 기록하고 다음 실패는 직전 성공을 보존한다. */
	@Test
	void writesAtomicSuccessAndFailureStatuses() throws Exception {
		Path fakeBin = Files.createDirectories(
				temporaryDirectory.resolve("fake-bin"));
		Path fakePgDump = fakeBin.resolve("pg_dump");
		writeExecutable(fakePgDump, """
				#!/usr/bin/env bash
				set -eu
				test "${PGSSLMODE}" = "require"
				test "${PGREQUIRESSL}" = "1"
				test "${PGHOST}" = "example.invalid"
				test "${PGDATABASE}" = "attend"
				test "${PGUSER}" = "runtime"
				test "${PGPASSWORD}" = "not-used-password"
				test -z "${PGSERVICE:-}"
				test -z "${PGSERVICEFILE:-}"
				output_file=''
				for argument in "$@"; do
				  case "${argument}" in
				    --file=*) output_file="${argument#--file=}" ;;
				  esac
				done
				printf 'fake-custom-format-dump' >"${output_file}"
				""");

		Path outputDirectory = temporaryDirectory.resolve("off-host-backups");
		Path statusFile = temporaryDirectory
				.resolve("runtime-status/status.properties");
		Map<String, String> environment = environment(
				fakeBin, outputDirectory, statusFile);

		ProcessResult success = runBackup(environment);
		String successfulStatus = Files.readString(statusFile);

		assertThat(success.exitCode()).isZero();
		assertThat(success.output())
				.contains("backup_file=", "checksum_file=", "completed_at=")
				.doesNotContain("not-used-password");
		assertThat(successfulStatus)
				.contains(
						"version=1",
						"result=SUCCESS",
						"storage-type=OBJECT_STORAGE")
				.doesNotContain(
						outputDirectory.toString(),
						"not-used-password",
						"checksum");
		String lastSuccessAt = property(successfulStatus, "last-success-at");
		Path dumpFile;
		try (var files = Files.list(outputDirectory)) {
			dumpFile = files
					.filter(path -> path.getFileName().toString().endsWith(".dump"))
					.findFirst()
					.orElseThrow();
		}
		writeExecutable(fakeBin.resolve("psql"), """
				#!/usr/bin/env bash
				set -eu
				test "${PGSSLMODE}" = "require"
				test "${PGREQUIRESSL}" = "1"
				test "${PGHOST}" = "example.invalid"
				test "${PGDATABASE}" = "attend"
				test "${PGUSER}" = "restore"
				test "${PGPASSWORD}" = "not-used-password"
				test -z "${PGSERVICE:-}"
				test -z "${PGSERVICEFILE:-}"
				for argument in "$@"; do
				  if [[ "${argument}" == '--command' ]]; then
				    printf '0\n'
				    exit 0
				  fi
				done
				while IFS= read -r _line; do :; done
				printf 'OK\n'
				""");
		writeExecutable(fakeBin.resolve("pg_restore"), """
				#!/usr/bin/env bash
				test "${PGSSLMODE}" = "require"
				test "${PGREQUIRESSL}" = "1"
				exit 0
				""");
		environment.put(
				"RESTORE_DATABASE_URL",
				"postgresql://example.invalid/attend");
		environment.put("RESTORE_DB_USERNAME", "restore");
		environment.put("RESTORE_DB_PASSWORD", "not-used-password");
		environment.put("RESTORE_DUMP_FILE", dumpFile.toString());

		ProcessResult restore = runRestoreVerification(environment);
		String restoredStatus = Files.readString(statusFile);

		assertThat(restore.exitCode()).isZero();
		assertThat(restore.output())
				.contains("restore_verification=OK")
				.doesNotContain("not-used-password");
		assertThat(restoredStatus)
				.contains(
						"result=SUCCESS",
						"last-success-at=" + lastSuccessAt,
						"last-restore-test-at=")
				.doesNotContain("not-used-password", dumpFile.toString());
		String lastRestoreAt = property(
				restoredStatus, "last-restore-test-at");

		writeExecutable(fakePgDump, """
				#!/usr/bin/env bash
				exit 9
				""");

		ProcessResult failure = runBackup(environment);
		String failedStatus = Files.readString(statusFile);

		assertThat(failure.exitCode()).isEqualTo(9);
		assertThat(failedStatus)
				.contains(
						"result=FAILURE",
						"last-success-at=" + lastSuccessAt,
						"storage-type=OBJECT_STORAGE",
						"last-restore-test-at=" + lastRestoreAt)
				.doesNotContain("not-used-password");

		environment.put("BACKUP_STORAGE_TYPE", "UNAPPROVED_LOCAL_PATH");
		ProcessResult configurationFailure = runBackup(environment);
		String configurationFailureStatus = Files.readString(statusFile);

		assertThat(configurationFailure.exitCode()).isEqualTo(2);
		assertThat(configurationFailureStatus)
				.contains(
						"result=FAILURE",
						"last-success-at=" + lastSuccessAt,
						"storage-type=OBJECT_STORAGE")
				.doesNotContain("UNAPPROVED_LOCAL_PATH");

		Path lockFile = Path.of(statusFile + ".lock");
		Files.writeString(lockFile, "stale-lock-file-without-an-owner");
		assertThat(lockFile).isRegularFile();
		environment.put("BACKUP_STORAGE_TYPE", "OBJECT_STORAGE");
		ProcessResult runWithExistingLockFile = runBackup(environment);

		assertThat(runWithExistingLockFile.exitCode()).isEqualTo(9);
		assertThat(runWithExistingLockFile.output())
				.doesNotContain("flock 또는 lockf를 찾을 수 없습니다");
	}

	/** 상태 파일이 없어도 동시 백업은 직렬화되고 서로 다른 파일로 보존된다. */
	@Test
	void serializesConcurrentBackupsWithoutAStatusFile() throws Exception {
		Path fakeBin = Files.createDirectories(
				temporaryDirectory.resolve("concurrent-fake-bin"));
		writeExecutable(fakeBin.resolve("pg_dump"), """
				#!/usr/bin/env bash
				set -eu
				test "${PGSSLMODE}" = "require"
				test "${PGREQUIRESSL}" = "1"
				output_file=''
				for argument in "$@"; do
				  case "${argument}" in
				    --file=*) output_file="${argument#--file=}" ;;
				  esac
				done
				sleep 1
				printf 'dump-%s' "$$" >"${output_file}"
				""");
		Path outputDirectory = temporaryDirectory.resolve("concurrent-backups");
		Map<String, String> environment = new HashMap<>(System.getenv());
		environment.put(
				"PATH", fakeBin + System.getProperty("path.separator")
						+ environment.getOrDefault("PATH", "/usr/bin:/bin"));
		environment.put(
				"BACKUP_DATABASE_URL",
				"postgresql://backup.example.test/attend");
		environment.put("BACKUP_DB_USERNAME", "backup");
		environment.put("BACKUP_DB_PASSWORD", "concurrent-secret");
		environment.put("BACKUP_OUTPUT_DIR", outputDirectory.toString());
		environment.remove("BACKUP_STATUS_FILE");

		Process first = scriptProcessBuilder(
				"ops/backup/backup.sh", environment).start();
		Process second = scriptProcessBuilder(
				"ops/backup/backup.sh", environment).start();
		String firstOutput = new String(
				first.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String secondOutput = new String(
				second.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(first.waitFor()).isZero();
		assertThat(second.waitFor()).isZero();
		assertThat(firstOutput).contains("backup_file=");
		assertThat(secondOutput).contains("backup_file=");
		try (var files = Files.list(outputDirectory)) {
			assertThat(files.filter(path -> path.getFileName().toString()
					.endsWith(".dump"))).hasSize(2);
		}
		try (var files = Files.list(outputDirectory)) {
			assertThat(files.filter(path -> path.getFileName().toString()
					.endsWith(".sha256"))).hasSize(2);
		}
	}

	/** URL option은 모두 거부해 encoded alias가 고정 TLS 환경을 덮지 못하게 한다. */
	@Test
	void rejectsConnectionOptionsThatCouldOverrideForcedTls() throws Exception {
		Path fakeBin = Files.createDirectories(
				temporaryDirectory.resolve("tls-fake-bin"));
		writeExecutable(fakeBin.resolve("pg_dump"), """
				#!/usr/bin/env bash
				exit 0
				""");
		Map<String, String> environment = new HashMap<>(System.getenv());
		environment.put(
				"PATH", fakeBin + System.getProperty("path.separator")
						+ environment.getOrDefault("PATH", "/usr/bin:/bin"));
		environment.put("BACKUP_OUTPUT_DIR",
				temporaryDirectory.resolve("tls-backups").toString());
		environment.put("BACKUP_DB_USERNAME", "backup");
		environment.put("BACKUP_DB_PASSWORD", "tls-test-secret");
		environment.remove("BACKUP_STATUS_FILE");
		environment.put("RESTORE_DUMP_FILE",
				temporaryDirectory.resolve("not-read.dump").toString());
		environment.put("RESTORE_DB_USERNAME", "restore");
		environment.put("RESTORE_DB_PASSWORD", "tls-test-secret");

		for (String query : List.of(
				"sslmode=disable",
				"sslmode=require&sslmode=disable",
				"sslmode=require&ss%6cmode=disable",
				"sslmode=require&requiressl=0")) {
			environment.put("BACKUP_DATABASE_URL",
					"postgresql://backup.example.test/attend?" + query);
			ProcessResult backup = runBackup(environment);
			assertThat(backup.exitCode()).isEqualTo(2);
			assertThat(backup.output())
					.contains("연결 옵션·credential")
					.doesNotContain("backup.example.test");

			environment.put("RESTORE_DATABASE_URL",
					"postgresql://restore.example.test/attend?" + query);
			ProcessResult restore = runRestoreVerification(environment);
			assertThat(restore.exitCode()).isEqualTo(2);
			assertThat(restore.output())
					.contains("연결 옵션·credential")
					.doesNotContain("restore.example.test");
		}
	}

	private Map<String, String> environment(
			Path fakeBin, Path outputDirectory, Path statusFile) {
		Map<String, String> environment = new HashMap<>(System.getenv());
		environment.put(
				"PATH", fakeBin + System.getProperty("path.separator")
						+ environment.getOrDefault("PATH", "/usr/bin:/bin"));
		environment.put(
				"BACKUP_DATABASE_URL",
				"postgresql://example.invalid/attend");
		environment.put("BACKUP_DB_USERNAME", "runtime");
		environment.put("BACKUP_DB_PASSWORD", "not-used-password");
		environment.put("BACKUP_OUTPUT_DIR", outputDirectory.toString());
		environment.put("BACKUP_STATUS_FILE", statusFile.toString());
		environment.put("BACKUP_STORAGE_TYPE", "OBJECT_STORAGE");
		return environment;
	}

	private static ProcessResult runBackup(Map<String, String> environment)
			throws IOException, InterruptedException {
		return runScript("ops/backup/backup.sh", environment);
	}

	private static ProcessResult runRestoreVerification(
			Map<String, String> environment)
			throws IOException, InterruptedException {
		return runScript("ops/backup/restore-verify.sh", environment);
	}

	private static ProcessResult runScript(
			String script, Map<String, String> environment)
			throws IOException, InterruptedException {
		Process process = scriptProcessBuilder(script, environment).start();
		String output = new String(
				process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new ProcessResult(process.waitFor(), output);
	}

	private static ProcessBuilder scriptProcessBuilder(
			String script, Map<String, String> environment) {
		Path scriptPath = Path.of(script).toAbsolutePath().normalize();
		ProcessBuilder builder = new ProcessBuilder(
				"/usr/bin/env", "bash", scriptPath.toString())
				.redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(environment);
		return builder;
	}

	private static void writeExecutable(Path path, String content)
			throws IOException {
		Files.writeString(path, content);
		assertThat(path.toFile().setExecutable(true)).isTrue();
	}

	private static String property(String content, String key) {
		return content.lines()
				.filter(line -> line.startsWith(key + "="))
				.map(line -> line.substring(key.length() + 1))
				.findFirst()
				.orElseThrow();
	}

	private record ProcessResult(int exitCode, String output) {
	}
}
