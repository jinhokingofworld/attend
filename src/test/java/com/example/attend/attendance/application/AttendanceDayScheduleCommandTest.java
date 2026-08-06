package com.example.attend.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

class AttendanceDayScheduleCommandTest {

	@Test
	void createsDailyDatesAtTheConfiguredInterval() {
		AttendanceDayScheduleCommand command = new AttendanceDayScheduleCommand(
				LocalDate.of(2026, 8, 1),
				LocalDate.of(2026, 8, 7),
				1L,
				AttendanceDayRecurrence.DAILY,
				2,
				Set.of(), Set.of(), null, null);

		assertThat(command.occurrenceDates()).containsExactly(
				LocalDate.of(2026, 8, 1),
				LocalDate.of(2026, 8, 3),
				LocalDate.of(2026, 8, 5),
				LocalDate.of(2026, 8, 7));
	}

	@Test
	void createsWeeklyDatesForSeveralSelectedWeekdays() {
		AttendanceDayScheduleCommand command = new AttendanceDayScheduleCommand(
				LocalDate.of(2026, 8, 3),
				LocalDate.of(2026, 8, 30),
				1L,
				AttendanceDayRecurrence.WEEKLY,
				2,
				Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
				Set.of(), null, null);

		assertThat(command.occurrenceDates()).containsExactly(
				LocalDate.of(2026, 8, 3),
				LocalDate.of(2026, 8, 5),
				LocalDate.of(2026, 8, 17),
				LocalDate.of(2026, 8, 19));
	}

	@Test
	void skipsMonthlyDatesThatDoNotExistInThatMonth() {
		AttendanceDayScheduleCommand command = new AttendanceDayScheduleCommand(
				LocalDate.of(2026, 1, 15),
				LocalDate.of(2026, 4, 30),
				1L,
				AttendanceDayRecurrence.MONTHLY,
				1,
				Set.of(), Set.of(1, 31), null, null);

		assertThat(command.occurrenceDates()).containsExactly(
				LocalDate.of(2026, 1, 31),
				LocalDate.of(2026, 2, 1),
				LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 3, 31),
				LocalDate.of(2026, 4, 1));
	}

	@Test
	void createsYearlyDatesForTheSelectedMonthAndDay() {
		AttendanceDayScheduleCommand command = new AttendanceDayScheduleCommand(
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2030, 12, 31),
				1L,
				AttendanceDayRecurrence.YEARLY,
				1,
				Set.of(), Set.of(), 2, 29);

		assertThat(command.occurrenceDates()).containsExactly(
				LocalDate.of(2028, 2, 29));
	}

	@Test
	void rejectsPlansLongerThanFiveYears() {
		assertThatThrownBy(() -> new AttendanceDayScheduleCommand(
					LocalDate.of(2026, 8, 1),
					LocalDate.of(2031, 8, 2),
					1L,
					AttendanceDayRecurrence.DAILY,
					1,
					Set.of(), Set.of(), null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("5년");
	}

	@Test
	void rejectsMoreThanTheMaximumOccurrencesPerRequest() {
		AttendanceDayScheduleCommand command = new AttendanceDayScheduleCommand(
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2027, 1, 2),
				1L,
				AttendanceDayRecurrence.DAILY,
				1,
				Set.of(), Set.of(), null, null);

		assertThatThrownBy(command::occurrenceDates)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("한 번에 생성할 수 있는 출석 날짜는 최대 366건입니다.");
	}

	@Test
	void rejectsAnOverflowingYearlyIntervalAsUserInput() {
		assertThatThrownBy(() -> new AttendanceDayScheduleCommand(
				LocalDate.of(2026, 1, 1),
				LocalDate.of(2030, 12, 31),
				1L,
				AttendanceDayRecurrence.YEARLY,
				Integer.MAX_VALUE,
				Set.of(), Set.of(), 1, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("반복 간격이 허용 범위를 초과했습니다.");
	}
}
