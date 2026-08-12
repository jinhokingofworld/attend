package com.example.attend.notification.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.attend.access.application.AdminWriteGate;
import com.example.attend.audit.application.AuditLogWriter;
import com.example.attend.config.TelegramProperties;
import com.example.attend.notification.domain.TelegramConnectionRow;
import com.example.attend.notification.infrastructure.mybatis.TelegramNotificationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** 시험 메시지와 연결 해제 transaction이 outbox 변경 신호를 정확히 발행하는지 검증한다. */
class TelegramConnectionServiceOutboxSignalTest {

    private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");

    @Test
    void publishesAWakeUpSignalAfterPersistingATestMessage() {
        TelegramNotificationMapper mapper = mock(TelegramNotificationMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(mapper.selectConnection(7L)).thenReturn(new TelegramConnectionRow(7L, NOW));
        when(mapper.insertTestOutbox(7L,
                "[시험] 출석 알림 Telegram 연결이 정상입니다.\n실제 출석 정보는 포함되지 않습니다."))
                .thenReturn(1);

        service(mapper, eventPublisher).requestTestMessage(7L);

        verify(eventPublisher).publishEvent(new AttendanceTelegramOutboxChanged(1));
    }

    @Test
    void doesNotPublishWhenTheTestOutboxWasNotPersisted() {
        TelegramNotificationMapper mapper = mock(TelegramNotificationMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(mapper.selectConnection(7L)).thenReturn(new TelegramConnectionRow(7L, NOW));
        when(mapper.insertTestOutbox(7L,
                "[시험] 출석 알림 Telegram 연결이 정상입니다.\n실제 출석 정보는 포함되지 않습니다."))
                .thenReturn(0);

        assertThatThrownBy(() -> service(mapper, eventPublisher).requestTestMessage(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persist Telegram test notification");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void publishesAWakeUpSignalWhenDisconnectCancelsPendingJobs() {
        TelegramNotificationMapper mapper = mock(TelegramNotificationMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(mapper.cancelPendingAccountOutbox(7L, NOW)).thenReturn(2);

        service(mapper, eventPublisher).disconnect(7L);

        verify(eventPublisher).publishEvent(new AttendanceTelegramOutboxChanged(2));
    }

    private static TelegramConnectionService service(
            TelegramNotificationMapper mapper,
            ApplicationEventPublisher eventPublisher) {
        return new TelegramConnectionService(
                new TelegramProperties(
                        true,
                        "bot-token",
                        "attend_bot",
                        "webhook-secret",
                        "link-token-pepper-that-is-at-least-32-bytes",
                        10,
                        30),
                mock(AdminWriteGate.class),
                mapper,
                mock(AuditLogWriter.class),
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
