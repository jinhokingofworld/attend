package com.example.attend.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
				exit 0
				""");
		environment.put(
				"RESTORE_DATABASE_URL",
				"postgresql://restore:not-used-password@example.invalid/attend");
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

	private Map<String, String> environment(
			Path fakeBin, Path outputDirectory, Path statusFile) {
		Map<String, String> environment = new HashMap<>(System.getenv());
		environment.put(
				"PATH", fakeBin + System.getProperty("path.separator")
						+ environment.getOrDefault("PATH", "/usr/bin:/bin"));
		environment.put(
				"BACKUP_DATABASE_URL",
				"postgresql://runtime:not-used-password@example.invalid/attend");
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
		Path scriptPath = Path.of(script)
				.toAbsolutePath().normalize();
		ProcessBuilder builder = new ProcessBuilder(
				"/usr/bin/env", "bash", scriptPath.toString())
				.redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(environment);
		Process process = builder.start();
		String output = new String(
				process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new ProcessResult(process.waitFor(), output);
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
