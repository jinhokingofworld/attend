package com.example.attend;

import org.junit.jupiter.api.Test;
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
	 * 애플리케이션 컨텍스트 초기화가 예외 없이 완료되는지를 검증한다.
	 *
	 * <p>메서드 본문이 비어 있는 이유는 테스트 메서드 실행 전에 수행되는
	 * {@link SpringBootTest} 컨텍스트 생성 자체가 검증 대상이기 때문이다.</p>
	 */
	@Test
	void contextLoads() {
	}

}
