package com.example.attend.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.attend.attendance.infrastructure.mybatis.AttendanceDayMapper;
import com.example.attend.config.AttendanceFinalizationSchedulerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DB에 저장되는 마감 lease와 5회 backoff 경계를 검증한다. */
class AttendanceFinalizationQueueServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");

	@Test
	void claimsReadyDaysWithSharedLease() {
		AttendanceDayMapper mapper = mock(AttendanceDayMapper.class);
		AttendanceFinalizationClaim claim =
				new AttendanceFinalizationClaim(31L, 0, 7L);
		when(mapper.selectReadyFinalizationDayIds(NOW, 20))
				.thenReturn(List.of(31L));
		when(mapper.claimFinalizationDay(
				31L, NOW, NOW.plus(Duration.ofMinutes(2))))
				.thenReturn(claim);
		AttendanceFinalizationQueueService service = service(mapper);

		assertThat(service.claimReadyDays()).containsExactly(claim);
	}

	@Test
	void schedulesFirstRetryOneMinuteAfterInitialFailure() {
		AttendanceDayMapper mapper = mock(AttendanceDayMapper.class);
		AttendanceFinalizationQueueService service = service(mapper);
		AttendanceFinalizationClaim claim =
				new AttendanceFinalizationClaim(41L, 0, 9L);
		when(mapper.markFinalizationFailure(
				41L, 9L, 1, NOW.plus(Duration.ofMinutes(1)),
				"IllegalStateException", NOW)).thenReturn(1);

		assertThat(service.recordFailure(
				claim, new IllegalStateException("temporary"))).isTrue();
	}

	@Test
	void exhaustsAfterTheFifthRetryFailure() {
		AttendanceDayMapper mapper = mock(AttendanceDayMapper.class);
		AttendanceFinalizationQueueService service = service(mapper);
		AttendanceFinalizationClaim fifthRetry =
				new AttendanceFinalizationClaim(42L, 5, 10L);
		when(mapper.markFinalizationFailure(
				42L, 10L, 6, null,
				"IllegalArgumentException", NOW)).thenReturn(1);

		assertThat(service.recordFailure(
				fifthRetry, new IllegalArgumentException("persistent"))).isTrue();
		verify(mapper).markFinalizationFailure(
				42L, 10L, 6, null,
				"IllegalArgumentException", NOW);
	}

	private static AttendanceFinalizationQueueService service(
			AttendanceDayMapper mapper
	) {
		AttendanceFinalizationSchedulerProperties properties =
				new AttendanceFinalizationSchedulerProperties(
						true, Duration.ofMinutes(2), Duration.ofMinutes(1), 20);
		return new AttendanceFinalizationQueueService(
				mapper, properties, Clock.fixed(NOW, ZoneOffset.UTC));
	}
}
