package com.example.attend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 PostgreSQL 연결과 Flyway migration을 포함해 Spring 애플리케이션 구성을 검증한다.
 *
 * <p>개별 기능 테스트가 아니라, 컴포넌트 탐색·설정 바인딩·MyBatis·보안 설정 등
 * 전체 애플리케이션 컨텍스트가 함께 만들어질 수 있는지를 확인하는 smoke test다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AttendApplicationTests {

	/**
	 * Spring Boot가 테스트 데이터소스로 자동 연결할 PostgreSQL 컨테이너다.
	 */
	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>("postgres:15-alpine");

	/**
	 * 실제 업무 코드에 주입되는 서버 시계다.
	 */
	@Autowired
	private Clock attendanceClock;

	/**
	 * 애플리케이션 컨텍스트와 출석 업무 시간대 설정을 검증한다.
	 *
	 * <p>컨텍스트 생성 자체가 smoke test의 주 검증 대상이며, 서버 환경이 달라도
	 * 출석 기준 시간대는 항상 Asia/Seoul이어야 한다.</p>
	 */
	@Test
	void contextLoads() {
		assertThat(attendanceClock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
	}

}
