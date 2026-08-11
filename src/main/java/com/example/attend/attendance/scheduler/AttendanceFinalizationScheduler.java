package com.example.attend.attendance.scheduler;

import com.example.attend.attendance.application.FinalizeAttendanceDayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 저장된 마감 시각이 지난 미마감 날짜를 찾아 날짜별 application 트랜잭션을 호출한다.
 *
 * <p>스케줄러는 실행 계기만 제공한다. 결석 생성과 날짜 마감 규칙은
 * {@link FinalizeAttendanceDayService}에 있으며, 한 날짜 실패가 다른 날짜의
 * catch-up을 막지 않도록 날짜마다 독립 트랜잭션으로 실행한다.</p>
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

	/**
	 * 설정된 간격마다 재기동 중 놓친 과거 날짜까지 모두 처리한다.
	 */
	@Scheduled(fixedDelayString = "${attendance.scheduler.fixed-delay-ms:300000}")
	public void catchUpPastDays() {
		for (long dayId : finalizationService.findPendingDayIds()) {
			try {
				finalizationService.finalizeDay(dayId);
			} catch (RuntimeException exception) {
				log.error("Attendance day finalization failed. dayId={}", dayId, exception);
			}
		}
	}
}
