package com.example.attend.operations.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Telegram 네트워크 호출을 다른 scheduler 작업과 격리한다. */
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
}
