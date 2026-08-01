package com.example.attend.access.web;

import java.util.Map;

/**
 * 실시간 대시보드 화면에 공개해도 되는 출석 행만 명시한 응답 모델이다.
 *
 * <p>조회용 Map에는 비고, 내부 레코드 식별자와 기록 출처도 들어 있지만 이 모델은
 * 화면이 실제로 사용하는 필드만 복사한다. 쿼리에 열이 추가돼도 JSON 응답 범위가
 * 자동으로 넓어지지 않는 허용 목록 역할을 한다.</p>
 *
 * @param memberId 교사 식별자
 * @param name 교사 이름
 * @param status 출석 결과, 아직 기록 전이면 {@code null}
 * @param bandLabel 세부 출석 단계, 아직 기록 전이면 {@code null}
 * @param checkedInAt 출석 시각 문자열, 아직 기록 전이면 {@code null}
 */
public record DashboardAttendanceRow(
		long memberId,
		String name,
		String status,
		String bandLabel,
		String checkedInAt
) {

	/**
	 * 내부 조회 행에서 공개 허용 필드만 새 응답 객체로 복사한다.
	 *
	 * @param row 부서 범위 출석 조회 행
	 * @return 대시보드 전용 공개 응답
	 */
	public static DashboardAttendanceRow from(Map<String, Object> row) {
		return new DashboardAttendanceRow(
				((Number) row.get("member_id")).longValue(),
				text(row.get("name")),
				text(row.get("status")),
				text(row.get("band_label_snapshot")),
				text(row.get("checked_in_at")));
	}

	/** 값이 없으면 JSON null을 유지하고, 있으면 표시 가능한 문자열로 바꾼다. */
	private static String text(Object value) {
		return value == null ? null : value.toString();
	}
}
