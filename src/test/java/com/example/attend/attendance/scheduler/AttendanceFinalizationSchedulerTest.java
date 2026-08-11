package com.example.attend.attendance.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.attend.attendance.application.FinalizeAttendanceDayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** 일일 정기 마감과 재기동 catch-up의 실행 경계를 검증한다. */
class AttendanceFinalizationSchedulerTest {

	/** 자정 cron과 Asia/Seoul 시간대를 명시적으로 고정한다. */
	@Test
	void schedulesDailyFinalizationAtSeoulMidnight() throws NoSuchMethodException {
		Scheduled scheduled = AttendanceFinalizationScheduler.class
				.getMethod("finalizeDueDaysAtMidnight")
				.getAnnotation(Scheduled.class);

		assertThat(scheduled.cron())
				.isEqualTo("${attendance.scheduler.daily-cron:0 0 0 * * *}");
		assertThat(scheduled.zone())
				.isEqualTo("${attendance.scheduler.zone:Asia/Seoul}");
	}

	/** 애플리케이션 준비 완료 이벤트가 재기동 catch-up을 호출한다. */
	@Test
	void catchesUpDueDaysAfterApplicationRestart() throws NoSuchMethodException {
		FinalizeAttendanceDayService service = mock(FinalizeAttendanceDayService.class);
		when(service.findPendingDayIds()).thenReturn(List.of(11L, 12L));
		AttendanceFinalizationScheduler scheduler =
				new AttendanceFinalizationScheduler(service);

		scheduler.catchUpAfterRestart();

		verify(service).finalizeDay(11L);
		verify(service).finalizeDay(12L);
		EventListener listener = AttendanceFinalizationScheduler.class
				.getMethod("catchUpAfterRestart")
				.getAnnotation(EventListener.class);
		assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
	}

	/** 한 날짜의 실패가 뒤 날짜의 마감을 막지 않는다. */
	@Test
	void continuesDailyFinalizationAfterOneDayFails() {
		FinalizeAttendanceDayService service = mock(FinalizeAttendanceDayService.class);
		when(service.findPendingDayIds()).thenReturn(List.of(21L, 22L));
		doThrow(new IllegalStateException("forced failure"))
				.when(service).finalizeDay(21L);
		AttendanceFinalizationScheduler scheduler =
				new AttendanceFinalizationScheduler(service);

		scheduler.finalizeDueDaysAtMidnight();

		verify(service).finalizeDay(21L);
		verify(service).finalizeDay(22L);
	}

	/** 마감 대상이 없으면 날짜별 쓰기 작업을 호출하지 않는다. */
	@Test
	void stopsWhenThereAreNoDueDays() {
		FinalizeAttendanceDayService service = mock(FinalizeAttendanceDayService.class);
		when(service.findPendingDayIds()).thenReturn(List.of());
		AttendanceFinalizationScheduler scheduler =
				new AttendanceFinalizationScheduler(service);

		scheduler.finalizeDueDaysAtMidnight();

		verify(service, never()).finalizeDay(anyLong());
	}
}
