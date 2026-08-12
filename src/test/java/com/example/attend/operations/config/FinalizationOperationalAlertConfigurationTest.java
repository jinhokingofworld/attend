package com.example.attend.operations.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class FinalizationOperationalAlertConfigurationTest {

    @Test
    void createsASingleDedicatedBoundedWorker() {
        ThreadPoolTaskExecutor executor =
                new FinalizationOperationalAlertConfiguration()
                        .finalizationOperationalAlertExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(executor.getQueueCapacity()).isEqualTo(100);
        assertThat(executor.getThreadNamePrefix())
                .isEqualTo("finalization-operational-alert-");
        executor.initialize();
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }
}
