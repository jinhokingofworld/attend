package com.example.attend.attendance.application;

import com.example.attend.attendance.infrastructure.mybatis.AttendanceBandCountRow;

import java.util.List;

/**
 * 교사의 공식 출석 통계와 같은 분모로 계산한 비율을 제공한다.
 *
 * @param totalCount 공식 대상 날짜 수
 * @param presentCount 정상 출석 수
 * @param lateCount 전체 지각 수
 * @param absentCount 결석 수
 * @param lateBands 저장된 지각 단계별 건수
 */
public record AttendanceStatistics(
		int totalCount,
		int presentCount,
		int lateCount,
		int absentCount,
		List<AttendanceBandCountRow> lateBands
) {

	/**
	 * 단계별 목록이 호출자에 의해 변경되지 않도록 복사한다.
	 */
	public AttendanceStatistics {
		lateBands = List.copyOf(lateBands);
	}

	/** @return 정상 출석률(0~100) */
	public double presentRate() {
		return percentage(presentCount);
	}

	/** @return 전체 지각률(0~100) */
	public double lateRate() {
		return percentage(lateCount);
	}

	/** @return 결석률(0~100) */
	public double absentRate() {
		return percentage(absentCount);
	}

	/**
	 * 모든 상태 비율에 같은 공식 대상 날짜 분모를 적용한다.
	 */
	private double percentage(int count) {
		return totalCount == 0 ? 0.0 : count * 100.0 / totalCount;
	}
}
