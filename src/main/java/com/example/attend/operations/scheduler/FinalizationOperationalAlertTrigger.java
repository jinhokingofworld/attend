package com.example.attend.operations.scheduler;

import com.example.attend.operations.application.FinalizationOperationalIncidentCreated;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** DB의 다음 전달·lease 시각에 운영 알림 전용 단일 wake-up을 예약한다. */
@Component
@ConditionalOnProperty(
        name = "attendance.operations.telegram.enabled", havingValue = "true")
public final class FinalizationOperationalAlertTrigger {
    private static final Logger log =
            LoggerFactory.getLogger(FinalizationOperationalAlertTrigger.class);
    private static final Duration INFRASTRUCTURE_RECOVERY_DELAY =
            Duration.ofMinutes(1);

    private final FinalizationOperationalAlertDispatcher dispatcher;
    private final TaskExecutor executor;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final Object monitor = new Object();

    private boolean stopped;
    private boolean workerActive;
    private boolean workRequested;
    private long scheduleGeneration;
    private Instant scheduledAt;
    private ScheduledFuture<?> scheduledTask;

    public FinalizationOperationalAlertTrigger(
            FinalizationOperationalAlertDispatcher dispatcher,
            @Qualifier("finalizationOperationalAlertExecutor") TaskExecutor executor,
            @Qualifier("finalizationOperationalAlertTaskScheduler")
                    TaskScheduler taskScheduler,
            Clock clock) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    /** 사건 transaction이 commit된 뒤 ready outbox 처리를 즉시 요청한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentCreated(FinalizationOperationalIncidentCreated event) {
        requestWorker("after-commit", event.eventId());
    }

    /** 재기동 시 ready outbox와 아직 유효한 lease의 다음 시각을 복구한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAfterApplicationReady() {
        requestWorker("startup", null);
    }

    private void requestWorker(String trigger, Long eventId) {
        synchronized (monitor) {
            if (stopped) {
                return;
            }
            workRequested = true;
            if (workerActive) {
                return;
            }
            workerActive = true;
        }

        try {
            executor.execute(this::runWorker);
        } catch (RuntimeException exception) {
            synchronized (monitor) {
                workerActive = false;
            }
            log.error(
                    "Operational alert dispatch submission rejected. trigger={}, eventId={}",
                    trigger, eventId, exception);
            scheduleInfrastructureRecovery("executor-recovery");
        }
    }

    private void runWorker() {
        boolean completedNormally = false;
        try {
            while (true) {
                synchronized (monitor) {
                    if (stopped) {
                        workRequested = false;
                        completedNormally = true;
                        return;
                    }
                    workRequested = false;
                }

                try {
                    dispatcher.recoverAndDispatchReady();
                    refreshScheduleOrRecover("dispatch-complete");
                } catch (RuntimeException exception) {
                    log.error("Operational alert dispatch failed.", exception);
                    scheduleInfrastructureRecovery("dispatch-recovery");
                }

                synchronized (monitor) {
                    if (stopped) {
                        workRequested = false;
                        completedNormally = true;
                        return;
                    }
                    if (workRequested) {
                        continue;
                    }
                    completedNormally = true;
                    return;
                }
            }
        } finally {
            boolean resubmit = false;
            boolean recover = false;
            synchronized (monitor) {
                workerActive = false;
                if (stopped) {
                    workRequested = false;
                } else if (!completedNormally) {
                    workRequested = true;
                    recover = true;
                } else if (workRequested) {
                    resubmit = true;
                }
            }
            if (recover) {
                scheduleInfrastructureRecovery("worker-abnormal-exit");
            } else if (resubmit) {
                requestWorker("worker-exit-race", null);
            }
        }
    }

    private void refreshScheduleOrRecover(String trigger) {
        try {
            replaceSchedule(dispatcher.findNextActionAt(), trigger);
        } catch (RuntimeException exception) {
            log.error(
                    "Could not refresh next operational alert delivery time. trigger={}",
                    trigger, exception);
            scheduleInfrastructureRecovery("schedule-refresh-recovery");
        }
    }

    private void replaceSchedule(Instant requestedAt, String trigger) {
        synchronized (monitor) {
            if (stopped) {
                return;
            }
            if (requestedAt == null) {
                ++scheduleGeneration;
                cancelScheduledTask();
                return;
            }
            Instant effectiveAt = clampToNow(requestedAt);
            if (effectiveAt.equals(scheduledAt)) {
                return;
            }
            replaceScheduledTask(effectiveAt, trigger);
        }
    }

    private void scheduleInfrastructureRecovery(String trigger) {
        try {
            scheduleIfEarlier(
                    clock.instant().plus(INFRASTRUCTURE_RECOVERY_DELAY), trigger);
        } catch (RuntimeException exception) {
            log.error(
                    "Could not schedule one-time operational alert recovery. trigger={}",
                    trigger, exception);
        }
    }

    private void scheduleIfEarlier(Instant requestedAt, String trigger) {
        synchronized (monitor) {
            if (stopped) {
                return;
            }
            Instant effectiveAt = clampToNow(requestedAt);
            if (scheduledAt != null && !scheduledAt.isAfter(effectiveAt)) {
                return;
            }
            replaceScheduledTask(effectiveAt, trigger);
        }
    }

    private void replaceScheduledTask(Instant effectiveAt, String trigger) {
        long acceptedGeneration = scheduleGeneration + 1;
        ScheduledFuture<?> acceptedTask = taskScheduler.schedule(
                () -> wakeUp(acceptedGeneration), effectiveAt);
        if (acceptedTask == null) {
            throw new IllegalStateException(
                    "TaskScheduler rejected operational alert task: " + trigger);
        }

        ScheduledFuture<?> previousTask = scheduledTask;
        scheduledTask = acceptedTask;
        scheduledAt = effectiveAt;
        scheduleGeneration = acceptedGeneration;
        if (previousTask != null) {
            previousTask.cancel(false);
        }
    }

    private void wakeUp(long generation) {
        synchronized (monitor) {
            if (stopped || generation != scheduleGeneration) {
                return;
            }
            ++scheduleGeneration;
            scheduledTask = null;
            scheduledAt = null;
        }
        requestWorker("scheduled-wake-up", null);
    }

    private Instant clampToNow(Instant requestedAt) {
        Instant now = clock.instant();
        return requestedAt.isBefore(now) ? now : requestedAt;
    }

    private void cancelScheduledTask() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduledTask = null;
        scheduledAt = null;
    }

    /** 애플리케이션 종료 시 로컬 wake-up을 취소하고 늦은 callback을 무효화한다. */
    @PreDestroy
    public void stop() {
        synchronized (monitor) {
            stopped = true;
            workRequested = false;
            ++scheduleGeneration;
            cancelScheduledTask();
        }
    }
}
