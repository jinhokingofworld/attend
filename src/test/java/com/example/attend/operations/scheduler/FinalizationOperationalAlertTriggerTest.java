package com.example.attend.operations.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.attend.operations.application.FinalizationOperationalIncidentCreated;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 고정 polling 없이 DB의 다음 운영 알림 시각만 예약하는지 검증한다. */
class FinalizationOperationalAlertTriggerTest {
    private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");

    @Test
    void schedulesTheDatabaseActionAfterApplicationStarts() throws Exception {
        Fixture fixture = fixture();
        Instant retryAt = NOW.plusSeconds(45);
        when(fixture.dispatcher().findNextActionAt()).thenReturn(retryAt);

        fixture.trigger().startAfterApplicationReady();

        assertThat(fixture.executor().tasks()).hasSize(1);
        verifyNoInteractions(fixture.dispatcher());
        fixture.executor().run(0);
        verify(fixture.dispatcher()).recoverAndDispatchReady();
        verify(fixture.taskScheduler()).schedule(any(Runnable.class), eq(retryAt));

        Method listener = FinalizationOperationalAlertTrigger.class.getMethod(
                "startAfterApplicationReady");
        assertThat(listener.getAnnotation(EventListener.class).value())
                .containsExactly(ApplicationReadyEvent.class);
        assertThat(FinalizationOperationalAlertTrigger.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class));
    }

    @Test
    void leavesNoTimerWhenTheOutboxHasNoPendingAction() {
        Fixture fixture = fixture();

        fixture.trigger().startAfterApplicationReady();
        fixture.executor().run(0);

        verify(fixture.dispatcher()).recoverAndDispatchReady();
        verify(fixture.dispatcher()).findNextActionAt();
        verify(fixture.taskScheduler(), never())
                .schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void submitsCommittedIncidentWithoutCallingTelegramInline() throws Exception {
        Fixture fixture = fixture();

        fixture.trigger().onIncidentCreated(
                new FinalizationOperationalIncidentCreated(51L));

        assertThat(fixture.executor().tasks()).hasSize(1);
        verifyNoInteractions(fixture.dispatcher());
        fixture.executor().run(0);
        verify(fixture.dispatcher()).recoverAndDispatchReady();

        TransactionalEventListener listener = FinalizationOperationalAlertTrigger.class
                .getMethod(
                        "onIncidentCreated",
                        FinalizationOperationalIncidentCreated.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void scheduledWakeUpOnlySubmitsWorkToTheDedicatedExecutor() {
        Fixture fixture = fixture();
        Instant retryAt = NOW.plusSeconds(45);
        when(fixture.dispatcher().findNextActionAt())
                .thenReturn(retryAt, (Instant) null);

        fixture.trigger().startAfterApplicationReady();
        fixture.executor().run(0);
        Runnable wakeUp = firstScheduledTask(fixture.taskScheduler());

        wakeUp.run();

        assertThat(fixture.executor().tasks()).hasSize(2);
        verify(fixture.dispatcher(), times(1)).recoverAndDispatchReady();
        fixture.executor().run(1);
        verify(fixture.dispatcher(), times(2)).recoverAndDispatchReady();
    }

    @Test
    void clampsAnOverdueDatabaseActionToNow() {
        Fixture fixture = fixture();
        when(fixture.dispatcher().findNextActionAt())
                .thenReturn(NOW.minusSeconds(30));

        fixture.trigger().startAfterApplicationReady();
        fixture.executor().run(0);

        verify(fixture.taskScheduler()).schedule(any(Runnable.class), eq(NOW));
    }

    @Test
    void coalescesWorkCommittedWhileTheWorkerIsDispatching()
            throws Exception {
        Fixture fixture = fixture();
        CountDownLatch dispatchStarted = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                dispatchStarted.countDown();
                if (!releaseDispatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test dispatch was not released");
                }
            }
            return null;
        }).when(fixture.dispatcher()).recoverAndDispatchReady();

        fixture.trigger().startAfterApplicationReady();
        try (ExecutorService runner = Executors.newSingleThreadExecutor()) {
            var worker = runner.submit(() -> fixture.executor().run(0));
            assertThat(dispatchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.trigger().onIncidentCreated(
                    new FinalizationOperationalIncidentCreated(52L));
            releaseDispatch.countDown();
            worker.get(5, TimeUnit.SECONDS);
        }

        assertThat(fixture.executor().tasks()).hasSize(1);
        verify(fixture.dispatcher(), times(2)).recoverAndDispatchReady();
        verify(fixture.dispatcher(), times(2)).findNextActionAt();
    }

    @Test
    void preservesWorkCommittedWhileTheWorkerReadsTheNextDatabaseAction()
            throws Exception {
        ScheduledFuture<?> laterFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> earlierFuture = mock(ScheduledFuture.class);
        Fixture fixture = fixture(laterFuture, earlierFuture);
        Instant later = NOW.plus(Duration.ofHours(1));
        Instant earlier = NOW.plusSeconds(30);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(fixture.dispatcher().findNextActionAt()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                refreshStarted.countDown();
                if (!releaseRefresh.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test refresh was not released");
                }
                return later;
            }
            return earlier;
        });

        fixture.trigger().startAfterApplicationReady();
        try (ExecutorService runner = Executors.newSingleThreadExecutor()) {
            var worker = runner.submit(() -> fixture.executor().run(0));
            assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.trigger().onIncidentCreated(
                    new FinalizationOperationalIncidentCreated(53L));
            releaseRefresh.countDown();
            worker.get(5, TimeUnit.SECONDS);
        }

        verify(fixture.taskScheduler()).schedule(any(Runnable.class), eq(later));
        verify(fixture.taskScheduler()).schedule(any(Runnable.class), eq(earlier));
        verify(laterFuture).cancel(false);
        verify(fixture.dispatcher(), times(2)).recoverAndDispatchReady();
        verify(fixture.dispatcher(), times(2)).findNextActionAt();
    }

    @Test
    void replacesALaterWakeUpAndIgnoresItsStaleCallback() {
        ScheduledFuture<?> laterFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> earlierFuture = mock(ScheduledFuture.class);
        Fixture fixture = fixture(laterFuture, earlierFuture);
        Instant later = NOW.plus(Duration.ofHours(1));
        Instant earlier = NOW.plusSeconds(30);
        when(fixture.dispatcher().findNextActionAt())
                .thenReturn(later, earlier, null);

        fixture.trigger().startAfterApplicationReady();
        fixture.executor().run(0);
        fixture.trigger().onIncidentCreated(
                new FinalizationOperationalIncidentCreated(54L));
        fixture.executor().run(1);

        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.taskScheduler()).schedule(callbacks.capture(), eq(later));
        verify(fixture.taskScheduler()).schedule(callbacks.capture(), eq(earlier));
        verify(laterFuture).cancel(false);

        callbacks.getAllValues().get(0).run();
        assertThat(fixture.executor().tasks()).hasSize(2);
        callbacks.getAllValues().get(1).run();
        assertThat(fixture.executor().tasks()).hasSize(3);
    }

    @Test
    void schedulesOneTimeRecoveryWhenExecutorSubmissionIsRejected() {
        FinalizationOperationalAlertDispatcher dispatcher =
                mock(FinalizationOperationalAlertDispatcher.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doThrow(new IllegalStateException("executor unavailable"))
                .doNothing()
                .when(executor).execute(any(Runnable.class));
        doReturn(future).when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        FinalizationOperationalAlertTrigger trigger =
                new FinalizationOperationalAlertTrigger(
                        dispatcher,
                        executor,
                        taskScheduler,
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(trigger::startAfterApplicationReady)
                .doesNotThrowAnyException();

        ArgumentCaptor<Runnable> recovery = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(
                recovery.capture(), eq(NOW.plus(Duration.ofMinutes(1))));
        recovery.getValue().run();
        verify(executor, times(2)).execute(any(Runnable.class));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void schedulesOneTimeRecoveryWhenTheInitialDatabaseWakeUpIsRejected() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> recoveryFuture = mock(ScheduledFuture.class);
        doThrow(new IllegalStateException("scheduler unavailable"))
                .doReturn(recoveryFuture)
                .when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        Fixture fixture = fixture(
                new RecordingTaskExecutor(), taskScheduler, recoveryFuture);
        when(fixture.dispatcher().findNextActionAt())
                .thenReturn(NOW.plusSeconds(30));

        fixture.trigger().startAfterApplicationReady();
        assertThatCode(() -> fixture.executor().run(0))
                .doesNotThrowAnyException();

        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> scheduledTimes = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(2))
                .schedule(callbacks.capture(), scheduledTimes.capture());
        assertThat(scheduledTimes.getAllValues()).containsExactly(
                NOW.plusSeconds(30),
                NOW.plus(Duration.ofMinutes(1)));

        callbacks.getAllValues().get(1).run();
        assertThat(fixture.executor().tasks()).hasSize(2);
    }

    @Test
    void replacesTheAcceptedWakeUpWithRecoveryWhenAnEarlierScheduleIsRejected() {
        ScheduledFuture<?> accepted = mock(ScheduledFuture.class);
        ScheduledFuture<?> recovery = mock(ScheduledFuture.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        doReturn(accepted)
                .doThrow(new IllegalStateException("scheduler unavailable"))
                .doReturn(recovery)
                .when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        Fixture fixture = fixture(new RecordingTaskExecutor(), taskScheduler, accepted);
        when(fixture.dispatcher().findNextActionAt()).thenReturn(
                NOW.plus(Duration.ofHours(1)),
                NOW.plusSeconds(30));

        fixture.trigger().startAfterApplicationReady();
        fixture.executor().run(0);
        fixture.trigger().onIncidentCreated(
                new FinalizationOperationalIncidentCreated(55L));

        assertThatCode(() -> fixture.executor().run(1))
                .doesNotThrowAnyException();
        ArgumentCaptor<Instant> scheduledTimes = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(3))
                .schedule(any(Runnable.class), scheduledTimes.capture());
        assertThat(scheduledTimes.getAllValues()).containsExactly(
                NOW.plus(Duration.ofHours(1)),
                NOW.plusSeconds(30),
                NOW.plus(Duration.ofMinutes(1)));
        verify(accepted).cancel(false);
    }

    @Test
    void schedulesOneTimeRecoveryWhenDispatchFails() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.dispatcher()).recoverAndDispatchReady();

        fixture.trigger().startAfterApplicationReady();
        assertThatCode(() -> fixture.executor().run(0))
                .doesNotThrowAnyException();

        verify(fixture.taskScheduler()).schedule(
                any(Runnable.class), eq(NOW.plus(Duration.ofMinutes(1))));
        verify(fixture.dispatcher(), never()).findNextActionAt();
    }

    @Test
    void schedulesOneTimeRecoveryWhenTheNextActionQueryFails() {
        Fixture fixture = fixture();
        when(fixture.dispatcher().findNextActionAt())
                .thenThrow(new IllegalStateException("database unavailable"));

        fixture.trigger().startAfterApplicationReady();
        assertThatCode(() -> fixture.executor().run(0))
                .doesNotThrowAnyException();

        verify(fixture.dispatcher()).recoverAndDispatchReady();
        verify(fixture.taskScheduler()).schedule(
                any(Runnable.class), eq(NOW.plus(Duration.ofMinutes(1))));
    }

    @Test
    void releasesTheWorkerAndSchedulesRecoveryAfterAnAbnormalError() {
        Fixture fixture = fixture();
        AssertionError failure = new AssertionError("worker failed abnormally");
        doThrow(failure)
                .doNothing()
                .when(fixture.dispatcher()).recoverAndDispatchReady();

        fixture.trigger().startAfterApplicationReady();

        assertThatThrownBy(() -> fixture.executor().run(0)).isSameAs(failure);
        verify(fixture.taskScheduler()).schedule(
                any(Runnable.class), eq(NOW.plus(Duration.ofMinutes(1))));

        fixture.trigger().onIncidentCreated(
                new FinalizationOperationalIncidentCreated(56L));
        assertThat(fixture.executor().tasks()).hasSize(2);
        assertThatCode(() -> fixture.executor().run(1)).doesNotThrowAnyException();
        verify(fixture.dispatcher(), times(2)).recoverAndDispatchReady();
    }

    @Test
    void cancelsThePendingWakeUpAndIgnoresItAfterShutdown() {
        Fixture fixture = fixture();
        when(fixture.dispatcher().findNextActionAt())
                .thenReturn(NOW.plusSeconds(30));
        fixture.trigger().startAfterApplicationReady();
        fixture.executor().run(0);
        Runnable wakeUp = firstScheduledTask(fixture.taskScheduler());

        fixture.trigger().stop();
        wakeUp.run();

        verify(fixture.future()).cancel(false);
        assertThat(fixture.executor().tasks()).hasSize(1);
    }

    private static Fixture fixture(ScheduledFuture<?>... futures) {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> retainedFuture;
        if (futures.length == 0) {
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> future = mock(ScheduledFuture.class);
            retainedFuture = future;
            doReturn(future).when(taskScheduler)
                    .schedule(any(Runnable.class), any(Instant.class));
        } else if (futures.length == 1) {
            retainedFuture = futures[0];
            doReturn(futures[0]).when(taskScheduler)
                    .schedule(any(Runnable.class), any(Instant.class));
        } else if (futures.length == 2) {
            retainedFuture = futures[0];
            doReturn(futures[0], futures[1]).when(taskScheduler)
                    .schedule(any(Runnable.class), any(Instant.class));
        } else {
            throw new IllegalArgumentException("Only two scheduled tasks are needed");
        }
        return fixture(new RecordingTaskExecutor(), taskScheduler, retainedFuture);
    }

    private static Fixture fixture(
            RecordingTaskExecutor executor,
            TaskScheduler taskScheduler,
            ScheduledFuture<?> future) {
        FinalizationOperationalAlertDispatcher dispatcher =
                mock(FinalizationOperationalAlertDispatcher.class);
        return new Fixture(
                dispatcher,
                executor,
                taskScheduler,
                future,
                new FinalizationOperationalAlertTrigger(
                        dispatcher,
                        executor,
                        taskScheduler,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static Runnable firstScheduledTask(TaskScheduler taskScheduler) {
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callback.capture(), any(Instant.class));
        return callback.getValue();
    }

    private record Fixture(
            FinalizationOperationalAlertDispatcher dispatcher,
            RecordingTaskExecutor executor,
            TaskScheduler taskScheduler,
            ScheduledFuture<?> future,
            FinalizationOperationalAlertTrigger trigger) {
    }

    private static final class RecordingTaskExecutor implements TaskExecutor {
        private final List<Runnable> tasks =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        List<Runnable> tasks() {
            synchronized (tasks) {
                return List.copyOf(tasks);
            }
        }

        void run(int index) {
            Runnable task;
            synchronized (tasks) {
                task = tasks.get(index);
            }
            task.run();
        }
    }
}
