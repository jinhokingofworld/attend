package com.example.attend.notification.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.attend.config.AdminSecurityProperties;
import com.example.attend.config.TelegramProperties;
import com.example.attend.notification.domain.FinalizationNotificationData;
import com.example.attend.notification.infrastructure.mybatis.TelegramNotificationMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** 마감 outbox 생산자가 실제 INSERT 결과에 맞춰 commit 신호를 발행하는지 검증한다. */
class AttendanceFinalizationNotificationPublisherTest {

    private static final long DAY_ID = 41L;
    private static final long DEPARTMENT_ID = 7L;

    @Test
    void publishesOneWakeUpSignalWithTheNumberOfInsertedRecipientJobs() {
        TelegramNotificationMapper mapper = mapperWithFinalizedDay();
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(mapper.selectConnectedActiveDepartmentAdminAccountIds(DEPARTMENT_ID))
                .thenReturn(List.of(101L, 102L));
        when(mapper.insertFinalizationOutbox(
                eq(DAY_ID), eq(DEPARTMENT_ID), eq(101L), anyString())).thenReturn(1);
        when(mapper.insertFinalizationOutbox(
                eq(DAY_ID), eq(DEPARTMENT_ID), eq(102L), anyString())).thenReturn(1);

        publisher(mapper, eventPublisher).enqueueForFinalizedDay(DAY_ID);

        verify(eventPublisher).publishEvent(new AttendanceTelegramOutboxChanged(2));
    }

    @Test
    void doesNotPublishAWakeUpSignalWhenEveryOutboxAlreadyExists() {
        TelegramNotificationMapper mapper = mapperWithFinalizedDay();
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(mapper.selectConnectedActiveDepartmentAdminAccountIds(DEPARTMENT_ID))
                .thenReturn(List.of(101L));
        when(mapper.insertFinalizationOutbox(
                eq(DAY_ID), eq(DEPARTMENT_ID), eq(101L), anyString())).thenReturn(0);

        publisher(mapper, eventPublisher).enqueueForFinalizedDay(DAY_ID);

        verify(eventPublisher, never()).publishEvent(new AttendanceTelegramOutboxChanged(1));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void doesNotPublishAWakeUpSignalWhenTheOutboxInsertFails() {
        TelegramNotificationMapper mapper = mapperWithFinalizedDay();
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(mapper.selectConnectedActiveDepartmentAdminAccountIds(DEPARTMENT_ID))
                .thenReturn(List.of(101L));
        when(mapper.insertFinalizationOutbox(
                eq(DAY_ID), eq(DEPARTMENT_ID), eq(101L), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> publisher(mapper, eventPublisher)
                .enqueueForFinalizedDay(DAY_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verifyNoInteractions(eventPublisher);
    }

    private static TelegramNotificationMapper mapperWithFinalizedDay() {
        TelegramNotificationMapper mapper = mock(TelegramNotificationMapper.class);
        when(mapper.selectFinalizationData(DAY_ID)).thenReturn(
                new FinalizationNotificationData(
                        DAY_ID,
                        DEPARTMENT_ID,
                        LocalDate.of(2026, 8, 12),
                        "유치부",
                        2,
                        2,
                        0,
                        0));
        when(mapper.selectFinalizationMembers(DAY_ID)).thenReturn(List.of());
        return mapper;
    }

    private static AttendanceFinalizationNotificationPublisher publisher(
            TelegramNotificationMapper mapper,
            ApplicationEventPublisher eventPublisher) {
        return new AttendanceFinalizationNotificationPublisher(
                new TelegramProperties(
                        true,
                        "bot-token",
                        "attend_bot",
                        "webhook-secret",
                        "link-token-pepper-that-is-at-least-32-bytes",
                        10,
                        30),
                new AdminSecurityProperties(true, "account-pepper", "https://attend.example"),
                mapper,
                eventPublisher);
    }
}
