package com.example.attend.attendance.scheduler;

import com.example.attend.attendance.application.AttendanceFinalizationClaim;
import com.example.attend.attendance.application.AttendanceFinalizationQueueService;
import com.example.attend.attendance.application.AttendanceFinalizationScheduleChanged;
import com.example.attend.attendance.application.FinalizeAttendanceDayService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** DB에 저장된 가장 이른 마감·재시도 시각에 맞춰 단일 wake-up을 동적으로 예약한다. */
@Component
@ConditionalOnProperty(name = "attendance.scheduler.enabled", havingValue = "true")
public class AttendanceFinalizationScheduler {

	private static final Logger log =
			LoggerFactory.getLogger(AttendanceFinalizationScheduler.class);

	private final AttendanceFinalizationQueueService queueService;
	private final FinalizeAttendanceDayService finalizationService;
	private final TaskScheduler taskScheduler;
	private final Clock clock;
	private final Object scheduleMonitor = new Object();

	private long scheduleGeneration;
	private Instant scheduledAt;
	private ScheduledFuture<?> scheduledTask;

	public AttendanceFinalizationScheduler(
			AttendanceFinalizationQueueService queueService,
			FinalizeAttendanceDayService finalizationService,
			TaskScheduler taskScheduler,
			Clock clock
	) {
		this.queueService = queueService;
		this.finalizationService = finalizationService;
		this.taskScheduler = taskScheduler;
		this.clock = clock;
	}

	/** 재기동 시 DB의 overdue 작업과 가장 가까운 미래 작업을 복구한다. */
	@EventListener(ApplicationReadyEvent.class)
	public void startAfterApplicationReady() {
		refreshScheduleOrRecover("startup");
	}

	/** 출석일 생성·정책 교체 transaction이 commit된 뒤 더 이른 due를 반영한다. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void scheduleChanged(AttendanceFinalizationScheduleChanged event) {
		scheduleIfEarlier(event.finalizationDueAt(), "attendance-day-changed");
	}

	private void dispatch(long generation) {
		synchronized (scheduleMonitor) {
			if (generation != scheduleGeneration) {
				return;
			}
			scheduledTask = null;
			scheduledAt = null;
		}

		try {
			while (true) {
				List<AttendanceFinalizationClaim> claims = queueService.claimReadyDays();
				for (AttendanceFinalizationClaim claim : claims) {
					finalizeClaim(claim);
				}
				if (claims.size() < queueService.claimLimit()) {
					break;
				}
			}
			refreshScheduleOrRecover("dispatch-complete");
		} catch (RuntimeException exception) {
			log.error("Attendance finalization dispatch failed.", exception);
			scheduleIfEarlier(
					clock.instant().plus(queueService.recoveryDelay()),
					"dispatch-recovery");
		}
	}

	private void finalizeClaim(AttendanceFinalizationClaim claim) {
		try {
			finalizationService.finalizeDay(claim.attendanceDayId());
		} catch (RuntimeException exception) {
			boolean recorded = queueService.recordFailure(claim, exception);
			if (!recorded) {
				log.warn(
						"Ignored stale finalization failure. dayId={}, claimVersion={}",
						claim.attendanceDayId(),
						claim.claimVersion());
				return;
			}
			int failureCount = claim.failureCount() + 1;
			if (failureCount > 5) {
				log.error(
						"Attendance finalization retries exhausted. dayId={}, attempts=6",
						claim.attendanceDayId(),
						exception);
			} else {
				log.warn(
						"Attendance finalization failed; retry scheduled. dayId={}, retry={}/5",
						claim.attendanceDayId(),
						failureCount,
						exception);
			}
		}
	}

	private void refreshScheduleOrRecover(String trigger) {
		long observedGeneration;
		synchronized (scheduleMonitor) {
			observedGeneration = scheduleGeneration;
		}
		try {
			Instant nextActionAt = queueService.findNextActionAt();
			replaceScheduleIfUnchanged(observedGeneration, nextActionAt, trigger);
		} catch (RuntimeException exception) {
			log.error("Could not read next attendance finalization time. trigger={}",
					trigger, exception);
			scheduleIfEarlier(
					clock.instant().plus(queueService.recoveryDelay()),
					"schedule-recovery");
		}
	}

	private void replaceScheduleIfUnchanged(
			long observedGeneration,
			Instant requestedAt,
			String trigger
	) {
		synchronized (scheduleMonitor) {
			if (observedGeneration != scheduleGeneration) {
				// refresh가 DB를 읽는 동안 commit event가 예약을 바꿨다면
				// DB에서 찾은 작업과 현재 예약 중 더 이른 시각을 보존한다.
				if (requestedAt != null) {
					scheduleIfEarlier(requestedAt, trigger + "-generation-changed");
				}
				return;
			}
			cancelScheduledTask();
			if (requestedAt != null) {
				schedule(requestedAt, trigger);
			} else {
				++scheduleGeneration;
			}
		}
	}

	private void scheduleIfEarlier(Instant requestedAt, String trigger) {
		if (requestedAt == null) {
			return;
		}
		synchronized (scheduleMonitor) {
			Instant effectiveAt = requestedAt.isBefore(clock.instant())
					? clock.instant() : requestedAt;
			if (scheduledAt != null && !scheduledAt.isAfter(effectiveAt)) {
				return;
			}
			cancelScheduledTask();
			schedule(effectiveAt, trigger);
		}
	}

	private void schedule(Instant requestedAt, String trigger) {
		Instant effectiveAt = requestedAt.isBefore(clock.instant())
				? clock.instant() : requestedAt;
		long generation = ++scheduleGeneration;
		try {
			ScheduledFuture<?> acceptedTask = taskScheduler.schedule(
					() -> dispatch(generation), effectiveAt);
			if (acceptedTask == null) {
				throw new IllegalStateException(
						"TaskScheduler rejected attendance finalization task: " + trigger);
			}
			scheduledTask = acceptedTask;
			scheduledAt = effectiveAt;
		} catch (RuntimeException exception) {
			scheduledTask = null;
			scheduledAt = null;
			throw exception;
		}
	}

	private void cancelScheduledTask() {
		if (scheduledTask != null) {
			scheduledTask.cancel(false);
		}
		scheduledTask = null;
		scheduledAt = null;
	}

	/** 애플리케이션 종료 시 아직 실행되지 않은 로컬 wake-up을 취소한다. */
	@PreDestroy
	public void stop() {
		synchronized (scheduleMonitor) {
			++scheduleGeneration;
			cancelScheduledTask();
		}
	}
}
