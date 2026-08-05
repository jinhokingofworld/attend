package com.example.attend.attendance.application;

/**
 * 출석 날짜를 생성할 때 사용할 달력 반복 단위다.
 */
public enum AttendanceDayRecurrence {

	/** 한 날짜만 생성한다. */
	NONE,

	/** 지정한 일수 간격으로 생성한다. */
	DAILY,

	/** 지정한 주수 간격과 요일로 생성한다. */
	WEEKLY,

	/** 지정한 개월 간격과 날짜로 생성한다. */
	MONTHLY,

	/** 지정한 년수 간격과 월·일로 생성한다. */
	YEARLY
}
