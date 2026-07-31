package com.example.attend.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 출석 업무에서 사용하는 서버 시각과 시간대를 제공한다.
 *
 * <p>업무 코드가 {@code LocalDateTime.now()}를 직접 호출하면 테스트 시각을 고정할 수 없고,
 * 서버의 운영체제 시간대에 따라 날짜와 출석 구간 판정이 달라질 수 있다. 따라서 애플리케이션은
 * 이 설정이 제공하는 {@link Clock}과 {@link ZoneId}를 주입받아 사용한다.</p>
 */
@Configuration
public class TimeConfiguration {

	/**
	 * MVP 전체에서 사용하는 교회 업무 시간대다.
	 */
	public static final ZoneId ATTENDANCE_ZONE = ZoneId.of("Asia/Seoul");

	/**
	 * 출석 날짜와 정책의 시각을 해석할 기준 시간대를 제공한다.
	 *
	 * @return Asia/Seoul 시간대
	 */
	@Bean
	public ZoneId attendanceZoneId() {
		return ATTENDANCE_ZONE;
	}

	/**
	 * 서버가 태깅 수신 시각을 한 번만 캡처할 때 사용할 시계를 제공한다.
	 *
	 * <p>테스트에서는 이 빈 대신 {@link Clock#fixed(java.time.Instant, ZoneId)}를 주입해
	 * 시작 시각과 각 출석 구간의 경계를 재현할 수 있다.</p>
	 *
	 * @param attendanceZoneId 출석 업무 기준 시간대
	 * @return 기준 시간대가 적용된 시스템 시계
	 */
	@Bean
	public Clock attendanceClock(ZoneId attendanceZoneId) {
		return Clock.system(attendanceZoneId);
	}
}
