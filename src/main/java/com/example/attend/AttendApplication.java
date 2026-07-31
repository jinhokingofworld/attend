package com.example.attend;

import com.example.attend.config.AttendanceProperties;
import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.config.DeviceApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Attend 애플리케이션의 실행 진입점이다.
 *
 * <p>{@link SpringBootApplication}이 컴포넌트 탐색과 자동 설정을 시작하고,
 * {@link EnableConfigurationProperties}가 출석 관련 설정값을 객체로 바인딩한다.
 * 실제 업무 로직은 이 클래스에 두지 않고 각 도메인 서비스로 분리한다.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties({
		AttendanceProperties.class,
		AdminSecurityProperties.class,
		DeviceApiProperties.class
})
public class AttendApplication {

	/**
	 * Spring Boot 애플리케이션을 시작한다.
	 *
	 * @param args JVM에서 전달된 명령행 인자
	 */
	public static void main(String[] args) {
		SpringApplication.run(AttendApplication.class, args);
	}

}
