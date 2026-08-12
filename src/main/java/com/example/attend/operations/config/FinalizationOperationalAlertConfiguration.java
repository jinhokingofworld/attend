package com.example.attend.operations.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 운영 Telegram의 네트워크 작업과 일회성 wake-up을 공용 scheduler에서 격리한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "attendance.operations.telegram.enabled", havingValue = "true")
public class FinalizationOperationalAlertConfiguration {

    @Bean(name = "finalizationOperationalAlertExecutor")
    ThreadPoolTaskExecutor finalizationOperationalAlertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("finalization-operational-alert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(25);
        return executor;
    }

    /**
     * 운영 알림 wake-up 전용 scheduler다.
     *
     * <p>{@code defaultCandidate=false}는 Spring Boot의 공용 {@code taskScheduler}
     * 자동설정을 유지한다. 따라서 일반 Telegram의 동기 HTTP 작업은 이 scheduler의
     * 정확한 wake-up을 지연시킬 수 없다.</p>
     */
    @Bean(
            name = "finalizationOperationalAlertTaskScheduler",
            defaultCandidate = false)
    ThreadPoolTaskScheduler finalizationOperationalAlertTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("finalization-operational-alert-wake-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
