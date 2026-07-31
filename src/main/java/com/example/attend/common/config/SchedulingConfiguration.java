package com.example.attend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link org.springframework.scheduling.annotation.Scheduled} 메서드 탐색을 활성화한다.
 *
 * <p>실제 마감 bean은 {@code attendance.scheduler.enabled=true}일 때만 생성되므로,
 * 이 설정만으로 운영 DB 쓰기가 시작되지는 않는다.</p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {
}
