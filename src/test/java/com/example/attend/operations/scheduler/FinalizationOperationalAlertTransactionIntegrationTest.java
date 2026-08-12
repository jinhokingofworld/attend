package com.example.attend.operations.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.attend.operations.application.FinalizationOperationalIncidentCreated;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@SpringJUnitConfig(FinalizationOperationalAlertTransactionIntegrationTest.Config.class)
class FinalizationOperationalAlertTransactionIntegrationTest {

    @Autowired
    private IncidentPublisher publisher;

    @Autowired
    private RecordingTaskExecutor executor;

    @Autowired
    private FinalizationOperationalAlertDispatcher dispatcher;

    @BeforeEach
    void resetFixture() {
        executor.clear();
        reset(dispatcher);
    }

    @Test
    void submitsImmediateDeliveryOnlyAfterTheIncidentTransactionCommits() {
        publisher.publish(51L, false);

        assertThat(executor.tasks()).hasSize(1);
        verifyNoInteractions(dispatcher);
        executor.tasks().getFirst().run();
        verify(dispatcher).recoverAndDispatchReady();
    }

    @Test
    void doesNotSubmitImmediateDeliveryWhenTheIncidentTransactionRollsBack() {
        assertThatThrownBy(() -> publisher.publish(52L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback");

        assertThat(executor.tasks()).isEmpty();
        verifyNoInteractions(dispatcher);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new StubTransactionManager();
        }

        @Bean
        FinalizationOperationalAlertDispatcher dispatcher() {
            return mock(FinalizationOperationalAlertDispatcher.class);
        }

        @Bean(name = "finalizationOperationalAlertExecutor")
        RecordingTaskExecutor executor() {
            return new RecordingTaskExecutor();
        }

        @Bean
        FinalizationOperationalAlertTrigger trigger(
                FinalizationOperationalAlertDispatcher dispatcher,
                RecordingTaskExecutor executor,
                TaskScheduler taskScheduler) {
            return new FinalizationOperationalAlertTrigger(
                    dispatcher,
                    executor,
                    taskScheduler,
                    Clock.fixed(
                            Instant.parse("2026-08-12T01:00:00Z"),
                            ZoneOffset.UTC));
        }

        @Bean
        TaskScheduler taskScheduler() {
            TaskScheduler scheduler = mock(TaskScheduler.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> future = mock(ScheduledFuture.class);
            doReturn(future).when(scheduler)
                    .schedule(any(Runnable.class), any(Instant.class));
            return scheduler;
        }

        @Bean
        IncidentPublisher incidentPublisher(ApplicationEventPublisher eventPublisher) {
            return new IncidentPublisher(eventPublisher);
        }
    }

    static class IncidentPublisher {
        private final ApplicationEventPublisher eventPublisher;

        IncidentPublisher(ApplicationEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
        }

        @Transactional
        public void publish(long eventId, boolean rollback) {
            eventPublisher.publishEvent(
                    new FinalizationOperationalIncidentCreated(eventId));
            if (rollback) {
                throw new IllegalStateException("rollback");
            }
        }
    }

    static final class RecordingTaskExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        List<Runnable> tasks() {
            return List.copyOf(tasks);
        }

        void clear() {
            tasks.clear();
        }
    }

    static final class StubTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
