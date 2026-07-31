package com.example.attend.device.application;

import java.time.OffsetDateTime;

/**
 * 새 NFC 출석 성공 때 장치에 반환하는 개인정보 없는 판정 결과다.
 *
 * @param attendanceStatus 정상 출석 또는 지각
 * @param attendanceBand 적용한 정책 구간
 * @param checkedInAt 서버가 확정한 최초 출석 시각
 */
public record CheckInData(
		String attendanceStatus,
		CheckInBandData attendanceBand,
		OffsetDateTime checkedInAt) {
}
