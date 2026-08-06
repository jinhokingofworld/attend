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
	private static final int MAX_OCCURRENCES_PER_REQUEST = 366;

	public AttendanceDayScheduleCommand {
		Objects.requireNonNull(startDate, "시작 날짜를 입력하세요.");
		Objects.requireNonNull(endDate, "반복 종료일을 입력하세요.");
		Objects.requireNonNull(recurrence, "반복 방식을 선택하세요.");
		if (policyVersionId <= 0) {
			throw new IllegalArgumentException("발행 정책을 선택하세요.");
		}
		if (endDate.isBefore(startDate)) {
			throw new IllegalArgumentException("반복 종료일은 시작 날짜보다 빠를 수 없습니다.");
		}
		if (endDate.isAfter(latestAllowedEndDate(startDate))) {
			throw new IllegalArgumentException(
					"반복 종료일은 시작 날짜부터 5년을 넘을 수 없습니다.");
		}
		if (interval <= 0) {
			throw new IllegalArgumentException("반복 간격은 1 이상이어야 합니다.");
		}
		validateInterval(recurrence, interval);
		weeklyDays = immutableWeekdays(weeklyDays);
		monthlyDays = immutableMonthDays(monthlyDays);

		switch (recurrence) {
			case NONE -> {
				if (!startDate.equals(endDate)) {
					throw new IllegalArgumentException(
							"반복하지 않을 때는 시작 날짜만 생성할 수 있습니다.");
				}
			}
			case WEEKLY -> require(!weeklyDays.isEmpty(),
					"주간 반복 요일을 하나 이상 선택하세요.");
			case MONTHLY -> require(!monthlyDays.isEmpty(),
					"월간 반복 날짜를 하나 이상 선택하세요.");
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
		List<LocalDate> sortedDates = dates.stream().sorted().toList();
		if (sortedDates.size() > MAX_OCCURRENCES_PER_REQUEST) {
			throw new IllegalArgumentException(
					"한 번에 생성할 수 있는 출석 날짜는 최대 366건입니다.");
		}
		return sortedDates;
	}

	private List<LocalDate> dailyDates() {
		List<LocalDate> dates = new ArrayList<>();
		LocalDate date = startDate;
		while (!date.isAfter(endDate)) {
			dates.add(date);
			if (ChronoUnit.DAYS.between(date, endDate) < interval) {
				break;
			}
			date = date.plusDays(interval);
		}
		return dates;
	}

	private List<LocalDate> weeklyDates() {
		List<LocalDate> dates = new ArrayList<>();
		int startWeekOffset = startDate.getDayOfWeek().getValue()
				- DayOfWeek.MONDAY.getValue();
		LocalDate date = startDate;
		while (!date.isAfter(endDate)) {
			long daysSinceStart = ChronoUnit.DAYS.between(startDate, date);
			long weeksSinceStart = Math.floorDiv(
					startWeekOffset + daysSinceStart, 7);
			if (weeksSinceStart % interval == 0 && weeklyDays.contains(date.getDayOfWeek())) {
				dates.add(date);
			}
			if (date.equals(endDate)) {
				break;
			}
			date = date.plusDays(1);
		}
		return dates;
	}

	private List<LocalDate> monthlyDates() {
		List<LocalDate> dates = new ArrayList<>();
		YearMonth lastMonth = YearMonth.from(endDate);
		YearMonth month = YearMonth.from(startDate);
		while (!month.isAfter(lastMonth)) {
			for (int day : monthlyDays) {
				if (day > month.lengthOfMonth()) {
					continue;
				}
				LocalDate date = month.atDay(day);
				if (!date.isBefore(startDate) && !date.isAfter(endDate)) {
					dates.add(date);
				}
			}
			if (ChronoUnit.MONTHS.between(month, lastMonth) < interval) {
				break;
			}
			month = month.plusMonths(interval);
		}
		return dates;
	}

	private List<LocalDate> yearlyDates() {
		List<LocalDate> dates = new ArrayList<>();
		long year = startDate.getYear();
		while (year <= endDate.getYear()) {
			YearMonth month = YearMonth.of((int) year, yearlyMonth);
			if (yearlyDay > month.lengthOfMonth()) {
				// 존재하지 않는 날짜는 해당 연도에 만들지 않는다.
			} else {
				LocalDate date = month.atDay(yearlyDay);
				if (!date.isBefore(startDate) && !date.isAfter(endDate)) {
					dates.add(date);
				}
			}
			long nextYear = year + (long) interval;
			if (nextYear > endDate.getYear()) {
				break;
			}
			year = nextYear;
		}
		return dates;
	}

	private static Set<DayOfWeek> immutableWeekdays(Set<DayOfWeek> weekdays) {
		if (weekdays == null || weekdays.isEmpty()) {
			return Set.of();
		}
		if (weekdays.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("반복 요일 값이 올바르지 않습니다.");
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
				throw new IllegalArgumentException("반복 날짜는 1일부터 31일 사이여야 합니다.");
			}
			result.add(day);
		}
		return Set.copyOf(result);
	}

	private static void validateYearlyDate(Integer month, Integer day) {
		if (month == null || month < 1 || month > 12) {
			throw new IllegalArgumentException("연간 반복 월은 1월부터 12월 사이여야 합니다.");
		}
		if (day == null || day < 1 || day > 31 || day > YearMonth.of(2024, month).lengthOfMonth()) {
			throw new IllegalArgumentException("선택한 월에 존재하는 반복 날짜를 선택하세요.");
		}
	}

	private static LocalDate latestAllowedEndDate(LocalDate startDate) {
		if (startDate.getYear() > LocalDate.MAX.getYear() - MAX_PLAN_YEARS) {
			return LocalDate.MAX;
		}
		return startDate.plusYears(MAX_PLAN_YEARS);
	}

	private static void validateInterval(
			AttendanceDayRecurrence recurrence,
			int interval
	) {
		int maximum = switch (recurrence) {
			case NONE -> 1;
			case DAILY -> MAX_PLAN_YEARS * 366;
			case WEEKLY -> MAX_PLAN_YEARS * 53;
			case MONTHLY -> MAX_PLAN_YEARS * 12;
			case YEARLY -> MAX_PLAN_YEARS;
		};
		if (interval > maximum) {
			throw new IllegalArgumentException("반복 간격이 허용 범위를 초과했습니다.");
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}
}
