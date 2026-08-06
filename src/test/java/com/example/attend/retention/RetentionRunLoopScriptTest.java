package com.example.attend.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** retention loop가 backlog 신호에 따라 즉시 후속 one-shot을 실행하는지 검증한다. */
class RetentionRunLoopScriptTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void retriesCatchUpBeforeUsingTheNormalInterval() throws Exception {
		Path fakeBin = Files.createDirectories(temporaryDirectory.resolve("bin"));
		Path stateFile = temporaryDirectory.resolve("runs");
		Path sleepsFile = temporaryDirectory.resolve("sleeps");
		writeExecutable(fakeBin.resolve("java"), """
				#!/bin/sh
				count=0
				if [ -f "${RETENTION_TEST_STATE}" ]; then
				  count="$(cat "${RETENTION_TEST_STATE}")"
				fi
				count=$((count + 1))
				printf '%s' "${count}" >"${RETENTION_TEST_STATE}"
				if [ "${count}" -eq 1 ]; then
				  printf '%s\n' 'retention=SUCCESS catchup_pending=true'
				else
				  printf '%s\n' 'retention=SUCCESS catchup_pending=false'
				fi
				""");
		writeExecutable(fakeBin.resolve("sleep"), """
				#!/bin/sh
				printf '%s\n' "$1" >>"${RETENTION_TEST_SLEEPS}"
				if [ "$1" = "86400" ]; then
				  exit 23
				fi
				""");

		ProcessBuilder builder = new ProcessBuilder(
				"/usr/bin/env",
				"sh",
				Path.of("ops/retention/run-loop.sh")
						.toAbsolutePath().normalize().toString())
				.redirectErrorStream(true);
		var environment = new HashMap<>(System.getenv());
		environment.put(
				"PATH",
				fakeBin + System.getProperty("path.separator") + "/usr/bin:/bin");
		environment.put("RETENTION_TEST_STATE", stateFile.toString());
		environment.put("RETENTION_TEST_SLEEPS", sleepsFile.toString());
		environment.put("RETENTION_RUN_INTERVAL_SECONDS", "86400");
		environment.put("RETENTION_CATCHUP_INTERVAL_SECONDS", "1");
		builder.environment().clear();
		builder.environment().putAll(environment);

		Process process = builder.start();
		String output = new String(
				process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(process.waitFor()).isEqualTo(23);
		assertThat(Files.readString(stateFile)).isEqualTo("2");
		assertThat(Files.readAllLines(sleepsFile)).containsExactly("1", "86400");
		assertThat(output)
				.contains("catchup_pending=true", "catchup_pending=false");
	}

	private static void writeExecutable(Path path, String content) throws Exception {
		Files.writeString(path, content);
		assertThat(path.toFile().setExecutable(true)).isTrue();
	}
}
