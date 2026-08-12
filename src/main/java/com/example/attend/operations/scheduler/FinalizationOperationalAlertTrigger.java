package com.example.attend.operations.scheduler;

import com.example.attend.operations.application.FinalizationOperationalIncidentCreated;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 즉시 전송과 영속 복구를 운영 알림 전용 executor에 제출한다. */
@Component
@ConditionalOnProperty(
        name = "attendance.operations.telegram.enabled", havingValue = "true")
public final class FinalizationOperationalAlertTrigger {
    private static final Logger log =
            LoggerFactory.getLogger(FinalizationOperationalAlertTrigger.class);

    private final FinalizationOperationalAlertDispatcher dispatcher;
    private final TaskExecutor executor;
    private final AtomicBoolean recoveryQueuedOrRunning = new AtomicBoolean();

    public FinalizationOperationalAlertTrigger(
            FinalizationOperationalAlertDispatcher dispatcher,
            @Qualifier("finalizationOperationalAlertExecutor") TaskExecutor executor) {
        this.dispatcher = dispatcher;
        this.executor = executor;
    }

    /** 사건 transaction이 commit된 뒤 해당 outbox를 즉시 전송하도록 요청한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentCreated(FinalizationOperationalIncidentCreated event) {
        submit(
                () -> dispatcher.dispatchById(event.eventId()),
                "after-commit",
                event.eventId());
    }

    /** 재기동으로 중단된 lease와 아직 보내지 못한 outbox를 즉시 복구한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAfterApplicationReady() {
        submitRecovery("startup");
    }

    /** 즉시 시도에서 누락된 outbox 전달만 60초마다 복구한다. */
    @Scheduled(
            fixedDelayString =
                    "${attendance.operations.telegram.dispatch-fixed-delay-ms:60000}",
            initialDelayString =
                    "${attendance.operations.telegram.dispatch-fixed-delay-ms:60000}")
    public void recoverAndDispatchOnSchedule() {
        submitRecovery("poll");
    }

    private void submitRecovery(String trigger) {
        if (!recoveryQueuedOrRunning.compareAndSet(false, true)) {
            return;
        }
        submit(
                dispatcher::recoverAndDispatchReady,
                trigger,
                null,
                recoveryQueuedOrRunning);
    }

    private void submit(Runnable task, String trigger, Long eventId) {
        submit(task, trigger, eventId, null);
    }

    private void submit(
            Runnable task,
            String trigger,
            Long eventId,
            AtomicBoolean completionFlag) {
        try {
            executor.execute(() -> {
                try {
                    runSafely(task, trigger, eventId);
                } finally {
                    if (completionFlag != null) {
                        completionFlag.set(false);
                    }
                }
            });
        } catch (RuntimeException exception) {
            if (completionFlag != null) {
                completionFlag.set(false);
            }
            log.error(
                    "Operational alert dispatch submission rejected. trigger={}, eventId={}",
                    trigger, eventId, exception);
        }
    }

    private void runSafely(Runnable task, String trigger, Long eventId) {
        try {
            task.run();
        } catch (RuntimeException exception) {
            log.error(
                    "Operational alert dispatch failed. trigger={}, eventId={}",
                    trigger, eventId, exception);
        }
    }
}
