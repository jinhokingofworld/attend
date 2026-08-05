package com.example.attend.attendance.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 한 번 또는 달력 반복 규칙으로 출석 날짜를 생성하는 입력이다.
 *
 * <p>반복 계획은 시작일을 기준으로 최대 5년까지 허용한다. 월별의 존재하지 않는
 * 날짜(예: 2월 30일)는 해당 월에 만들지 않는다.</p>
 */
public record AttendanceDayScheduleCommand(
		LocalDate startDate,
		LocalDate endDate,
		long policyVersionId,
		AttendanceDayRecurrence recurrence,
		int interval,
		Set<DayOfWeek> weeklyDays,
		Set<Integer> monthlyDays,
		Integer yearlyMonth,
		Integer yearlyDay
) {

	private static final int MAX_PLAN_YEARS = 5;

	public AttendanceDayScheduleCommand {
		Objects.requireNonNull(startDate, "startDate must not be null");
		Objects.requireNonNull(endDate, "endDate must not be null");
		Objects.requireNonNull(recurrence, "recurrence must not be null");
		if (policyVersionId <= 0) {
			throw new IllegalArgumentException("policyVersionId must be positive");
		}
		if (endDate.isBefore(startDate)) {
			throw new IllegalArgumentException("endDate must not be before startDate");
		}
		if (endDate.isAfter(startDate.plusYears(MAX_PLAN_YEARS))) {
			throw new IllegalArgumentException(
					"attendance plan must not exceed five years from its start date");
		}
		if (interval <= 0) {
			throw new IllegalArgumentException("interval must be positive");
		}
		weeklyDays = immutableWeekdays(weeklyDays);
		monthlyDays = immutableMonthDays(monthlyDays);

		switch (recurrence) {
			case NONE -> {
				if (!startDate.equals(endDate)) {
					throw new IllegalArgumentException(
							"a non-recurring attendance day must end on its start date");
				}
			}
			case WEEKLY -> require(!weeklyDays.isEmpty(),
					"weekly recurrence requires at least one weekday");
			case MONTHLY -> require(!monthlyDays.isEmpty(),
					"monthly recurrence requires at least one day of month");
			case YEARLY -> validateYearlyDate(yearlyMonth, yearlyDay);
			case DAILY -> {
				// The interval is sufficient.
			}
		}
	}

	/** 반환 범위 안에서 반복 규칙에 맞는 날짜를 오름차순으로 계산한다. */
	public List<LocalDate> occurrenceDates() {
		List<LocalDate> dates = switch (recurrence) {
			case NONE -> List.of(startDate);
			case DAILY -> dailyDates();
			case WEEKLY -> weeklyDates();
			case MONTHLY -> monthlyDates();
			case YEARLY -> yearlyDates();
		};
		return dates.stream().sorted().toList();
	}

	private List<LocalDate> dailyDates() {
		List<LocalDate> dates = new ArrayList<>();
		for (LocalDate date = startDate; !date.isAfter(endDate);
				 date = date.plusDays(interval)) {
			dates.add(date);
		}
		return dates;
	}

	private List<LocalDate> weeklyDates() {
		List<LocalDate> dates = new ArrayList<>();
		LocalDate firstWeekStart = startDate.minusDays(
				startDate.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
		for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
			LocalDate weekStart = date.minusDays(
					date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
			long weeksSinceStart = ChronoUnit.WEEKS.between(firstWeekStart, weekStart);
			if (weeksSinceStart % interval == 0 && weeklyDays.contains(date.getDayOfWeek())) {
				dates.add(date);
			}
		}
		return dates;
	}

	private List<LocalDate> monthlyDates() {
		List<LocalDate> dates = new ArrayList<>();
		YearMonth lastMonth = YearMonth.from(endDate);
		for (YearMonth month = YearMonth.from(startDate); !month.isAfter(lastMonth);
				 month = month.plusMonths(interval)) {
			for (int day : monthlyDays) {
				if (day > month.lengthOfMonth()) {
					continue;
				}
				LocalDate date = month.atDay(day);
				if (!date.isBefore(startDate) && !date.isAfter(endDate)) {
					dates.add(date);
				}
			}
		}
		return dates;
	}

	private List<LocalDate> yearlyDates() {
		List<LocalDate> dates = new ArrayList<>();
		for (int year = startDate.getYear(); year <= endDate.getYear(); year += interval) {
			YearMonth month = YearMonth.of(year, yearlyMonth);
			if (yearlyDay > month.lengthOfMonth()) {
				continue;
			}
			LocalDate date = month.atDay(yearlyDay);
			if (!date.isBefore(startDate) && !date.isAfter(endDate)) {
				dates.add(date);
			}
		}
		return dates;
	}

	private static Set<DayOfWeek> immutableWeekdays(Set<DayOfWeek> weekdays) {
		if (weekdays == null || weekdays.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(EnumSet.copyOf(weekdays));
	}

	private static Set<Integer> immutableMonthDays(Set<Integer> days) {
		if (days == null || days.isEmpty()) {
			return Set.of();
		}
		TreeSet<Integer> result = new TreeSet<>();
		for (Integer day : days) {
			if (day == null || day < 1 || day > 31) {
				throw new IllegalArgumentException("monthly day must be between 1 and 31");
			}
			result.add(day);
		}
		return Set.copyOf(result);
	}

	private static void validateYearlyDate(Integer month, Integer day) {
		if (month == null || month < 1 || month > 12) {
			throw new IllegalArgumentException("yearly month must be between 1 and 12");
		}
		if (day == null || day < 1 || day > 31 || day > YearMonth.of(2024, month).lengthOfMonth()) {
			throw new IllegalArgumentException("yearly day is not valid for its month");
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}
}
