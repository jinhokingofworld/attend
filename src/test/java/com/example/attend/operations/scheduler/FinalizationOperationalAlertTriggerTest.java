package com.example.attend.operations.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.attend.operations.application.FinalizationOperationalIncidentCreated;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class FinalizationOperationalAlertTriggerTest {

    @Test
    void submitsAfterCommitDeliveryWithoutCallingTelegramInline() throws Exception {
        FinalizationOperationalAlertDispatcher dispatcher =
                mock(FinalizationOperationalAlertDispatcher.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        FinalizationOperationalAlertTrigger trigger =
                new FinalizationOperationalAlertTrigger(dispatcher, executor);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        trigger.onIncidentCreated(new FinalizationOperationalIncidentCreated(51L));

        verify(executor).execute(task.capture());
        verifyNoInteractions(dispatcher);
        task.getValue().run();
        verify(dispatcher).dispatchById(51L);

        Method listener = FinalizationOperationalAlertTrigger.class.getMethod(
                "onIncidentCreated", FinalizationOperationalIncidentCreated.class);
        TransactionalEventListener annotation =
                listener.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void submitsStartupRecoveryToTheDedicatedExecutor() throws Exception {
        FinalizationOperationalAlertDispatcher dispatcher =
                mock(FinalizationOperationalAlertDispatcher.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        FinalizationOperationalAlertTrigger trigger =
                new FinalizationOperationalAlertTrigger(dispatcher, executor);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        trigger.startAfterApplicationReady();

        verify(executor).execute(task.capture());
        verifyNoInteractions(dispatcher);
        task.getValue().run();
        verify(dispatcher).recoverAndDispatchReady();

        Method listener = FinalizationOperationalAlertTrigger.class.getMethod(
                "startAfterApplicationReady");
        assertThat(listener.getAnnotation(EventListener.class).value())
                .containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    void usesSixtySecondPollingOnlyAsDeliveryRecovery() throws Exception {
        FinalizationOperationalAlertDispatcher dispatcher =
                mock(FinalizationOperationalAlertDispatcher.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        FinalizationOperationalAlertTrigger trigger =
                new FinalizationOperationalAlertTrigger(dispatcher, executor);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        trigger.recoverAndDispatchOnSchedule();

        verify(executor).execute(task.capture());
        verifyNoInteractions(dispatcher);
        task.getValue().run();
        verify(dispatcher).recoverAndDispatchReady();

        Method scheduledMethod = FinalizationOperationalAlertTrigger.class.getMethod(
                "recoverAndDispatchOnSchedule");
        Scheduled annotation = scheduledMethod.getAnnotation(Scheduled.class);
        assertThat(annotation.fixedDelayString()).isEqualTo(
                "${attendance.operations.telegram.dispatch-fixed-delay-ms:60000}");
        assertThat(annotation.initialDelayString()).isEqualTo(
                "${attendance.operations.telegram.dispatch-fixed-delay-ms:60000}");
    }

    @Test
    void coalescesStartupAndPollingRecoveryWhileOneRecoveryIsQueued() {
        FinalizationOperationalAlertDispatcher dispatcher =
                mock(FinalizationOperationalAlertDispatcher.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        FinalizationOperationalAlertTrigger trigger =
                new FinalizationOperationalAlertTrigger(dispatcher, executor);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        trigger.startAfterApplicationReady();
        trigger.recoverAndDispatchOnSchedule();

        verify(executor, times(1)).execute(task.capture());
        task.getValue().run();
        trigger.recoverAndDispatchOnSchedule();
        verify(executor, times(2)).execute(task.capture());
    }

    @Test
    void leavesCommittedOutboxForPollingWhenExecutorSubmissionIsRejected() {
        FinalizationOperationalAlertDispatcher dispatcher =
                mock(FinalizationOperationalAlertDispatcher.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        FinalizationOperationalAlertTrigger trigger =
                new FinalizationOperationalAlertTrigger(dispatcher, executor);
        doThrow(new IllegalStateException("executor unavailable"))
                .doNothing()
                .when(executor).execute(any(Runnable.class));

        assertThatCode(trigger::startAfterApplicationReady).doesNotThrowAnyException();
        assertThatCode(trigger::recoverAndDispatchOnSchedule).doesNotThrowAnyException();

        verify(executor, times(2)).execute(any(Runnable.class));
        verifyNoInteractions(dispatcher);
    }
}
