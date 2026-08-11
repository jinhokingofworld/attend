package com.example.attend.attendance.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.attend.attendance.application.AttendanceFinalizationClaim;
import com.example.attend.attendance.application.AttendanceFinalizationQueueService;
import com.example.attend.attendance.application.AttendanceFinalizationScheduleChanged;
import com.example.attend.attendance.application.FinalizeAttendanceDayService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 고정 polling 없는 동적 마감 예약과 실패 격리를 검증한다. */
class AttendanceFinalizationSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

	@Test
	void schedulesDatabaseDueTimeAfterApplicationStarts() throws NoSuchMethodException {
		Instant dueAt = NOW.plusSeconds(90);
		Fixture fixture = fixture();
		when(fixture.queueService().findNextActionAt()).thenReturn(dueAt);

		fixture.scheduler().startAfterApplicationReady();

		verify(fixture.taskScheduler()).schedule(any(Runnable.class), eq(dueAt));
		Method start = AttendanceFinalizationScheduler.class
				.getMethod("startAfterApplicationReady");
		assertThat(start.getAnnotation(EventListener.class).value())
				.containsExactly(ApplicationReadyEvent.class);
		assertThat(AttendanceFinalizationScheduler.class.getDeclaredMethods())
				.noneMatch(method -> method.isAnnotationPresent(Scheduled.class));
	}

	@Test
	void acceptsScheduleChangesOnlyAfterCommit() throws NoSuchMethodException {
		Instant earlierDueAt = NOW.plusSeconds(30);
		Fixture fixture = fixture();

		fixture.scheduler().scheduleChanged(
				new AttendanceFinalizationScheduleChanged(earlierDueAt));

		verify(fixture.taskScheduler()).schedule(any(Runnable.class), eq(earlierDueAt));
		TransactionalEventListener listener = AttendanceFinalizationScheduler.class
				.getMethod("scheduleChanged", AttendanceFinalizationScheduleChanged.class)
				.getAnnotation(TransactionalEventListener.class);
		assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
	}

	@Test
	void recordsFailureAndContinuesWithTheNextClaim() {
		Fixture fixture = fixture();
		AttendanceFinalizationClaim first = new AttendanceFinalizationClaim(21L, 0, 3L);
		AttendanceFinalizationClaim second = new AttendanceFinalizationClaim(22L, 0, 4L);
		when(fixture.queueService().findNextActionAt())
				.thenReturn(NOW, (Instant) null);
		when(fixture.queueService().claimReadyDays())
				.thenReturn(List.of(first, second), List.of());
		when(fixture.queueService().recordFailure(eq(first), any(IllegalStateException.class)))
				.thenReturn(true);
		doThrow(failure("forced"))
				.when(fixture.finalizationService()).finalizeDay(21L);

		fixture.scheduler().startAfterApplicationReady();
		runFirstScheduledTask(fixture.taskScheduler());

		verify(fixture.finalizationService()).finalizeDay(21L);
		verify(fixture.finalizationService()).finalizeDay(22L);
		verify(fixture.queueService()).recordFailure(eq(first), any(IllegalStateException.class));
	}

	@Test
	void retriesScheduleDiscoveryAfterInfrastructureFailure() {
		Fixture fixture = fixture();
		when(fixture.queueService().findNextActionAt())
				.thenThrow(new IllegalStateException("database unavailable"));

		fixture.scheduler().startAfterApplicationReady();

		verify(fixture.taskScheduler()).schedule(
				any(Runnable.class), eq(NOW.plus(Duration.ofMinutes(1))));
	}

	@Test
	void preservesEarlierDatabaseWorkWhenCommitEventRacesWithRefresh()
			throws Exception {
		Instant databaseDueAt = NOW.plusSeconds(30);
		Instant eventDueAt = NOW.plusSeconds(90);
		CountDownLatch queryStarted = new CountDownLatch(1);
		CountDownLatch releaseQuery = new CountDownLatch(1);
		Fixture fixture = fixture();
		when(fixture.queueService().findNextActionAt()).thenAnswer(invocation -> {
			queryStarted.countDown();
			if (!releaseQuery.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("test query was not released");
			}
			return databaseDueAt;
		});

		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			var refresh = executor.submit(
					() -> fixture.scheduler().startAfterApplicationReady());
			assertThat(queryStarted.await(5, TimeUnit.SECONDS)).isTrue();

			fixture.scheduler().scheduleChanged(
					new AttendanceFinalizationScheduleChanged(eventDueAt));
			releaseQuery.countDown();
			refresh.get(5, TimeUnit.SECONDS);
		}

		verify(fixture.taskScheduler()).schedule(any(Runnable.class), eq(eventDueAt));
		verify(fixture.taskScheduler()).schedule(any(Runnable.class), eq(databaseDueAt));
		verify(fixture.future()).cancel(false);
	}

	@Test
	void clearsRejectedScheduleStateBeforeRecoveryAttempt() {
		Fixture fixture = fixture();
		Instant dueAt = NOW.plusSeconds(30);
		when(fixture.queueService().findNextActionAt()).thenReturn(dueAt);
		doThrow(new IllegalStateException("scheduler unavailable"))
				.doReturn(fixture.future())
				.when(fixture.taskScheduler())
				.schedule(any(Runnable.class), any(Instant.class));

		fixture.scheduler().startAfterApplicationReady();

		verify(fixture.taskScheduler(), times(2))
				.schedule(any(Runnable.class), any(Instant.class));
		verify(fixture.taskScheduler()).schedule(
				any(Runnable.class), eq(NOW.plus(Duration.ofMinutes(1))));
	}

	private static Fixture fixture() {
		AttendanceFinalizationQueueService queueService =
				mock(AttendanceFinalizationQueueService.class);
		FinalizeAttendanceDayService finalizationService =
				mock(FinalizeAttendanceDayService.class);
		TaskScheduler taskScheduler = mock(TaskScheduler.class);
		@SuppressWarnings("unchecked")
		ScheduledFuture<Object> future = mock(ScheduledFuture.class);
		doReturn(future).when(taskScheduler)
				.schedule(any(Runnable.class), any(Instant.class));
		when(queueService.claimLimit()).thenReturn(20);
		when(queueService.recoveryDelay()).thenReturn(Duration.ofMinutes(1));
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		return new Fixture(
				queueService,
				finalizationService,
				taskScheduler,
				future,
				new AttendanceFinalizationScheduler(
						queueService, finalizationService, taskScheduler, clock));
	}

	private static void runFirstScheduledTask(TaskScheduler taskScheduler) {
		ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
		verify(taskScheduler).schedule(captor.capture(), any(Instant.class));
		captor.getValue().run();
	}

	private static IllegalStateException failure(String message) {
		return new IllegalStateException(message);
	}

	private record Fixture(
			AttendanceFinalizationQueueService queueService,
			FinalizeAttendanceDayService finalizationService,
			TaskScheduler taskScheduler,
			ScheduledFuture<?> future,
			AttendanceFinalizationScheduler scheduler
	) {
	}
}
