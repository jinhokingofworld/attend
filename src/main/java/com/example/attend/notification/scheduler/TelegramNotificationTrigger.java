package com.example.attend.notification.scheduler;

import com.example.attend.notification.application.AttendanceTelegramOutboxChanged;
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

/** DB의 다음 일반 출석 Telegram 전달·lease 시각에 단일 wake-up을 예약한다. */
@Component
@ConditionalOnProperty(name = "attendance.telegram.enabled", havingValue = "true")
public final class TelegramNotificationTrigger {
    private static final Logger log =
            LoggerFactory.getLogger(TelegramNotificationTrigger.class);
    private static final Duration INFRASTRUCTURE_RECOVERY_DELAY =
            Duration.ofMinutes(1);
    private static final Duration INFRASTRUCTURE_RECOVERY_FALLBACK_DELAY =
            Duration.ofMinutes(5);

    private final TelegramNotificationDispatcher dispatcher;
    private final TaskExecutor executor;
    private final TaskScheduler taskScheduler;
    private final TaskScheduler fallbackTaskScheduler;
    private final Clock clock;
    private final Object monitor = new Object();

    private boolean stopped;
    private boolean workerActive;
    private boolean workRequested;
    private Instant scheduledAt;
    private ScheduledFuture<?> scheduledTask;
    private WakeUpRegistration scheduledRegistration;

    public TelegramNotificationTrigger(
            TelegramNotificationDispatcher dispatcher,
            @Qualifier("attendanceTelegramExecutor") TaskExecutor executor,
            @Qualifier("attendanceTelegramTaskScheduler") TaskScheduler taskScheduler,
            @Qualifier("taskScheduler") TaskScheduler fallbackTaskScheduler,
            Clock clock) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.taskScheduler = taskScheduler;
        this.fallbackTaskScheduler = fallbackTaskScheduler;
        this.clock = clock;
    }

    /** outbox 변경 transaction이 commit된 뒤 ready 작업을 즉시 요청한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxChanged(AttendanceTelegramOutboxChanged event) {
        requestWorker("after-commit", event.affectedCount());
    }

    /** 재기동 시 ready 작업과 아직 유효한 lease의 다음 시각을 복구한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAfterApplicationReady() {
        requestWorker("startup", null);
    }

    private void requestWorker(String trigger, Integer affectedCount) {
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
                    "Telegram dispatch submission rejected. trigger={}, affectedCount={}",
                    trigger, affectedCount, exception);
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
                    log.error("Telegram notification dispatch failed.", exception);
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
                    "Could not refresh next Telegram notification time. trigger={}",
                    trigger, exception);
            scheduleInfrastructureRecovery("schedule-refresh-recovery");
        }
    }

    private void replaceSchedule(Instant requestedAt, String trigger) {
        boolean wakeImmediately = false;
        synchronized (monitor) {
            if (stopped) {
                return;
            }
            if (requestedAt == null) {
                cancelScheduledTask();
                return;
            }
            Instant now = clock.instant();
            if (!requestedAt.isAfter(now)) {
                cancelScheduledTask();
                workRequested = true;
                return;
            }
            if (requestedAt.equals(scheduledAt)) {
                return;
            }
            wakeImmediately = replaceScheduledTask(requestedAt, trigger);
        }
        if (wakeImmediately) {
            requestWorker("early-scheduled-callback", null);
        }
    }

    private void scheduleInfrastructureRecovery(String trigger) {
        boolean wakeImmediately = false;
        try {
            wakeImmediately = scheduleInfrastructureRecoveryAt(
                    taskScheduler, trigger, INFRASTRUCTURE_RECOVERY_DELAY);
        } catch (RuntimeException exception) {
            log.error(
                    "Could not schedule primary Telegram notification recovery. trigger={}",
                    trigger, exception);
            try {
                wakeImmediately = scheduleInfrastructureRecoveryAt(
                        fallbackTaskScheduler,
                        trigger + "-fallback",
                        INFRASTRUCTURE_RECOVERY_FALLBACK_DELAY);
            } catch (RuntimeException fallbackException) {
                log.error(
                        "Could not schedule fallback Telegram notification recovery. trigger={}",
                        trigger, fallbackException);
            }
        }
        if (wakeImmediately) {
            requestWorker("early-recovery-callback", null);
        }
    }

    private boolean scheduleInfrastructureRecoveryAt(
            TaskScheduler scheduler,
            String trigger,
            Duration delay) {
        synchronized (monitor) {
            if (stopped) {
                return false;
            }
            Instant requestedAt = clock.instant().plus(delay);
            if (scheduledAt != null && !scheduledAt.isAfter(requestedAt)) {
                return false;
            }
            return replaceScheduledTask(scheduler, requestedAt, trigger);
        }
    }

    /**
     * 새 task가 실제로 수락된 뒤에만 기존 task를 교체한다.
     *
     * <p>zero-delay callback이 {@code schedule()} 반환보다 먼저 실행돼도 신호를
     * 버리지 않고 worker 요청으로 전환한다.</p>
     */
    private boolean replaceScheduledTask(Instant requestedAt, String trigger) {
        return replaceScheduledTask(taskScheduler, requestedAt, trigger);
    }

    private boolean replaceScheduledTask(
            TaskScheduler scheduler,
            Instant requestedAt,
            String trigger) {
        WakeUpRegistration registration = new WakeUpRegistration();
        ScheduledFuture<?> acceptedTask = scheduler.schedule(registration, requestedAt);
        if (acceptedTask == null) {
            throw new IllegalStateException(
                    "TaskScheduler rejected Telegram notification task: " + trigger);
        }
        if (registration.firedBeforeAcceptance) {
            acceptedTask.cancel(false);
            return true;
        }

        ScheduledFuture<?> previousTask = scheduledTask;
        registration.accepted = true;
        scheduledRegistration = registration;
        scheduledTask = acceptedTask;
        scheduledAt = requestedAt;
        if (previousTask != null) {
            previousTask.cancel(false);
        }
        return false;
    }

    private void cancelScheduledTask() {
        scheduledRegistration = null;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduledTask = null;
        scheduledAt = null;
    }

    /** 애플리케이션 종료 시 아직 실행되지 않은 로컬 wake-up을 취소한다. */
    @PreDestroy
    public void stop() {
        synchronized (monitor) {
            stopped = true;
            workRequested = false;
            cancelScheduledTask();
        }
    }

    private final class WakeUpRegistration implements Runnable {
        private boolean accepted;
        private boolean firedBeforeAcceptance;

        @Override
        public void run() {
            synchronized (monitor) {
                if (!accepted) {
                    firedBeforeAcceptance = true;
                    return;
                }
                if (stopped || scheduledRegistration != this) {
                    return;
                }
                scheduledRegistration = null;
                scheduledTask = null;
                scheduledAt = null;
            }
            requestWorker("scheduled-wake-up", null);
        }
    }
}
