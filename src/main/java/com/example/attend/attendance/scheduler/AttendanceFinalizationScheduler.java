package com.example.attend.attendance.scheduler;

import com.example.attend.attendance.application.FinalizeAttendanceDayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일일 정기 마감과 재기동 catch-up의 실행 계기를 제공한다.
 *
 * <p>두 실행 경로 모두 같은 마감 서비스를 호출한다. 결석 생성과 날짜 마감 규칙은
 * {@link FinalizeAttendanceDayService}에 있으며, 한 날짜 실패가 다른 날짜의
 * 처리를 막지 않도록 날짜마다 독립 트랜잭션으로 실행한다.</p>
 */
@Component
@ConditionalOnProperty(
		name = "attendance.scheduler.enabled",
		havingValue = "true")
public class AttendanceFinalizationScheduler {

	private static final Logger log =
			LoggerFactory.getLogger(AttendanceFinalizationScheduler.class);

	private final FinalizeAttendanceDayService finalizationService;

	/**
	 * 날짜별 마감 서비스를 주입받는다.
	 *
	 * @param finalizationService 자동 결석과 마감 application service
	 */
	public AttendanceFinalizationScheduler(
			FinalizeAttendanceDayService finalizationService
	) {
		this.finalizationService = finalizationService;
	}

	/** Asia/Seoul 기준 매일 자정에 마감 대상 전체를 처리한다. */
	@Scheduled(
			cron = "${attendance.scheduler.daily-cron:0 0 0 * * *}",
			zone = "${attendance.scheduler.zone:Asia/Seoul}")
	public void finalizeDueDaysAtMidnight() {
		finalizeDueDays("daily-midnight");
	}

	/** 애플리케이션 재기동 뒤 놓친 마감 대상을 한 번 catch-up한다. */
	@EventListener(ApplicationReadyEvent.class)
	public void catchUpAfterRestart() {
		finalizeDueDays("startup-catch-up");
	}

	private void finalizeDueDays(String trigger) {
		for (long dayId : finalizationService.findPendingDayIds()) {
			try {
				finalizationService.finalizeDay(dayId);
			} catch (RuntimeException exception) {
				log.error(
						"Attendance day finalization failed. trigger={}, dayId={}",
						trigger,
						dayId,
						exception);
			}
		}
	}
}
