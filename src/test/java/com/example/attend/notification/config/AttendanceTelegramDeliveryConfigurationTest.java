package com.example.attend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.attend.common.config.SchedulingConfiguration;
import com.example.attend.notification.scheduler.TelegramNotificationDispatcher;
import com.example.attend.notification.scheduler.TelegramNotificationTrigger;
import com.example.attend.operations.config.FinalizationOperationalAlertConfiguration;
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

class AttendanceTelegramDeliveryConfigurationTest {

    @Test
    void createsASingleDedicatedBoundedNetworkWorker() {
        ThreadPoolTaskExecutor executor =
                new AttendanceTelegramDeliveryConfiguration()
                        .attendanceTelegramExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(executor.getQueueCapacity()).isEqualTo(100);
        assertThat(executor.getThreadNamePrefix())
                .isEqualTo("attendance-telegram-delivery-");
        executor.initialize();
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void keepsSharedAndBothTelegramWakeUpSchedulersIsolated() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(
                        SchedulingConfiguration.class,
                        AttendanceTelegramDeliveryConfiguration.class,
                        FinalizationOperationalAlertConfiguration.class,
                        TriggerFixtureConfiguration.class)
                .withPropertyValues(
                        "attendance.telegram.enabled=true",
                        "attendance.operations.telegram.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TaskScheduler shared = context.getBean(
                            "taskScheduler", TaskScheduler.class);
                    ThreadPoolTaskScheduler attendance = context.getBean(
                            "attendanceTelegramTaskScheduler",
                            ThreadPoolTaskScheduler.class);
                    ThreadPoolTaskScheduler operations = context.getBean(
                            "finalizationOperationalAlertTaskScheduler",
                            ThreadPoolTaskScheduler.class);

                    assertThat(attendance).isNotSameAs(shared).isNotSameAs(operations);
                    assertThat(operations).isNotSameAs(shared);
                    assertThat(context.getBeansOfType(TaskScheduler.class))
                            .containsKeys(
                                    "taskScheduler",
                                    "attendanceTelegramTaskScheduler",
                                    "finalizationOperationalAlertTaskScheduler")
                            .hasSize(3);
                    assertThat(attendance.getScheduledThreadPoolExecutor()
                            .getCorePoolSize()).isEqualTo(1);
                    assertThat(attendance.getThreadNamePrefix())
                            .isEqualTo("attendance-telegram-wake-");
                    assertThat(attendance.isRemoveOnCancelPolicy()).isTrue();
                    assertThat(attendance.getScheduledThreadPoolExecutor()
                            .getExecuteExistingDelayedTasksAfterShutdownPolicy())
                            .isFalse();
                    assertThat(attendance.getScheduledThreadPoolExecutor()
                            .getRejectedExecutionHandler())
                            .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

                    TelegramNotificationTrigger attendanceTrigger =
                            context.getBean(TelegramNotificationTrigger.class);
                    assertThat(ReflectionTestUtils.getField(
                            attendanceTrigger, "taskScheduler")).isSameAs(attendance);
                    assertThat(ReflectionTestUtils.getField(
                            attendanceTrigger, "fallbackTaskScheduler")).isSameAs(shared);
                    FinalizationOperationalAlertTrigger operationsTrigger =
                            context.getBean(FinalizationOperationalAlertTrigger.class);
                    assertThat(ReflectionTestUtils.getField(
                            operationsTrigger, "taskScheduler")).isSameAs(operations);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            TelegramNotificationTrigger.class,
            FinalizationOperationalAlertTrigger.class
    })
    static class TriggerFixtureConfiguration {

        @Bean
        TelegramNotificationDispatcher telegramNotificationDispatcher() {
            return mock(TelegramNotificationDispatcher.class);
        }

        @Bean
        FinalizationOperationalAlertDispatcher finalizationOperationalAlertDispatcher() {
            return mock(FinalizationOperationalAlertDispatcher.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
