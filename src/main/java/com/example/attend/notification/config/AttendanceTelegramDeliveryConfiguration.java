package com.example.attend.notification.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 일반 출석 Telegram의 네트워크 작업과 단발 wake-up을 각각 격리한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "attendance.telegram.enabled", havingValue = "true")
public class AttendanceTelegramDeliveryConfiguration {

    @Bean(name = "attendanceTelegramExecutor")
    ThreadPoolTaskExecutor attendanceTelegramExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("attendance-telegram-delivery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 진행 중 전송은 DB lease가 재기동 뒤 복구하므로 종료를 오래 막지 않는다.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /** 공용 scheduler와 개발자 운영 알림 scheduler를 대체하지 않는 전용 timer다. */
    @Bean(name = "attendanceTelegramTaskScheduler", defaultCandidate = false)
    ThreadPoolTaskScheduler attendanceTelegramTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("attendance-telegram-wake-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
