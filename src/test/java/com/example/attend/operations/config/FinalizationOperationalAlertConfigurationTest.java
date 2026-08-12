package com.example.attend.operations.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.attend.common.config.SchedulingConfiguration;
import com.example.attend.operations.scheduler.FinalizationOperationalAlertDispatcher;
import com.example.attend.operations.scheduler.FinalizationOperationalAlertTrigger;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    void createsAnIsolatedWakeUpSchedulerWithoutReplacingTheSharedScheduler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(
                        SchedulingConfiguration.class,
                        FinalizationOperationalAlertConfiguration.class,
                        TriggerFixtureConfiguration.class)
                .withPropertyValues("attendance.operations.telegram.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TaskScheduler shared = context.getBean(
                            "taskScheduler", TaskScheduler.class);
                    ThreadPoolTaskScheduler dedicated = context.getBean(
                            "finalizationOperationalAlertTaskScheduler",
                            ThreadPoolTaskScheduler.class);

                    assertThat(dedicated).isNotSameAs(shared);
                    assertThat(context.getBeansOfType(TaskScheduler.class))
                            .containsKeys(
                                    "taskScheduler",
                                    "finalizationOperationalAlertTaskScheduler")
                            .hasSize(2);
                    assertThat(dedicated.getScheduledThreadPoolExecutor()
                            .getCorePoolSize()).isEqualTo(1);
                    assertThat(dedicated.getThreadNamePrefix())
                            .isEqualTo("finalization-operational-alert-wake-");
                    assertThat(dedicated.isRemoveOnCancelPolicy()).isTrue();
                    assertThat(dedicated.getScheduledThreadPoolExecutor()
                            .getContinueExistingPeriodicTasksAfterShutdownPolicy())
                            .isFalse();
                    assertThat(dedicated.getScheduledThreadPoolExecutor()
                            .getExecuteExistingDelayedTasksAfterShutdownPolicy())
                            .isFalse();
                    assertThat(dedicated.getScheduledThreadPoolExecutor()
                            .getRejectedExecutionHandler())
                            .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

                    FinalizationOperationalAlertTrigger trigger =
                            context.getBean(FinalizationOperationalAlertTrigger.class);
                    assertThat(ReflectionTestUtils.getField(trigger, "taskScheduler"))
                            .isSameAs(dedicated);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(FinalizationOperationalAlertTrigger.class)
    static class TriggerFixtureConfiguration {

        @Bean
        FinalizationOperationalAlertDispatcher dispatcher() {
            return mock(FinalizationOperationalAlertDispatcher.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
