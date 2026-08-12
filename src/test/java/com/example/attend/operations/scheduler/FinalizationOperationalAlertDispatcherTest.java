package com.example.attend.operations.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.attend.config.OperationalTelegramProperties;
import com.example.attend.notification.infrastructure.telegram.TelegramBotClient;
import com.example.attend.notification.infrastructure.telegram.TelegramDeliveryFailure;
import com.example.attend.operations.application.FinalizationOperationalAlertFormatter;
import com.example.attend.operations.domain.FinalizationOperationalAlertJob;
import com.example.attend.operations.infrastructure.mybatis.FinalizationOperationalEventMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinalizationOperationalAlertDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");

    @Test
    void sendsClaimedEventWithTheDedicatedBotAndFencesTheSuccessUpdate() {
        Fixture fixture = fixture();
        when(fixture.mapper().claimEvent(51L, NOW, NOW.plusSeconds(120)))
                .thenReturn(job());
        when(fixture.client().sendMessage("operations-token", -100123L, "safe message"))
                .thenReturn(700L);

        fixture.dispatcher().dispatchById(51L);

        verify(fixture.client()).sendMessage("operations-token", -100123L, "safe message");
        verify(fixture.mapper()).markSent(51L, 4L, 700L, NOW);
    }

    @Test
    void recoversExpiredLeasesBeforeClaimingTheReadyBatch() {
        Fixture fixture = fixture();
        when(fixture.mapper().selectReadyEventIds(NOW, 20)).thenReturn(List.of(51L));
        when(fixture.mapper().claimEvent(51L, NOW, NOW.plusSeconds(120)))
                .thenReturn(job());
        when(fixture.client().sendMessage("operations-token", -100123L, "safe message"))
                .thenReturn(700L);

        fixture.dispatcher().recoverAndDispatchReady();

        verify(fixture.mapper()).recoverExpiredLeases(NOW);
        verify(fixture.mapper()).selectReadyEventIds(NOW, 20);
        verify(fixture.mapper()).markSent(51L, 4L, 700L, NOW);
    }

    @Test
    void persistsTelegramRetryAfterWithoutDiscardingTheIncident() {
        Fixture fixture = fixture();
        when(fixture.mapper().claimEvent(51L, NOW, NOW.plusSeconds(120)))
                .thenReturn(job());
        when(fixture.client().sendMessage("operations-token", -100123L, "safe message"))
                .thenThrow(new TelegramDeliveryFailure(
                        false, false, 45, "TELEGRAM_HTTP_429", null));

        fixture.dispatcher().dispatchById(51L);

        verify(fixture.mapper()).markRetry(
                51L, 4L, NOW.plusSeconds(45), "TELEGRAM_HTTP_429", NOW);
    }

    @Test
    void sendsOnlyOnceWhenImmediateAndRecoveryDispatchCompeteForTheSameEvent() {
        Fixture fixture = fixture();
        when(fixture.mapper().claimEvent(51L, NOW, NOW.plusSeconds(120)))
                .thenReturn(job(), (FinalizationOperationalAlertJob) null);
        when(fixture.client().sendMessage("operations-token", -100123L, "safe message"))
                .thenReturn(700L);

        fixture.dispatcher().dispatchById(51L);
        fixture.dispatcher().dispatchById(51L);

        verify(fixture.client(), times(1))
                .sendMessage("operations-token", -100123L, "safe message");
    }

    @Test
    void readsTheExactNextDeliveryOrLeaseTimeFromTheOutbox() {
        Fixture fixture = fixture();
        Instant nextActionAt = NOW.plusSeconds(45);
        when(fixture.mapper().selectNextDeliveryActionAt()).thenReturn(nextActionAt);

        assertThat(fixture.dispatcher().findNextActionAt()).isEqualTo(nextActionAt);
    }

    private static Fixture fixture() {
        FinalizationOperationalEventMapper mapper =
                mock(FinalizationOperationalEventMapper.class);
        FinalizationOperationalAlertFormatter formatter =
                mock(FinalizationOperationalAlertFormatter.class);
        TelegramBotClient client = mock(TelegramBotClient.class);
        when(formatter.format(job())).thenReturn("safe message");
        OperationalTelegramProperties properties =
                new OperationalTelegramProperties(
                        true, "operations-token", -100123L);
        return new Fixture(
                mapper,
                client,
                new FinalizationOperationalAlertDispatcher(
                        mapper,
                        formatter,
                        client,
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static FinalizationOperationalAlertJob job() {
        return new FinalizationOperationalAlertJob(
                51L,
                "FINALIZATION_RETRY_EXHAUSTED",
                31L,
                3L,
                2L,
                "유치부",
                LocalDate.of(2026, 8, 12),
                NOW.minusSeconds(300),
                NOW,
                6,
                "IllegalStateException",
                1,
                4L);
    }

    private record Fixture(
            FinalizationOperationalEventMapper mapper,
            TelegramBotClient client,
            FinalizationOperationalAlertDispatcher dispatcher) {
    }
}
