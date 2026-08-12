package com.example.attend.notification.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.attend.config.TelegramProperties;
import com.example.attend.notification.domain.TelegramDispatchJob;
import com.example.attend.notification.infrastructure.mybatis.TelegramNotificationMapper;
import com.example.attend.notification.infrastructure.telegram.TelegramBotClient;
import com.example.attend.notification.infrastructure.telegram.TelegramDeliveryFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TelegramNotificationDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");
    private static final Instant CONNECTION_UPDATED_AT = NOW.minusSeconds(60);

    @Test
    void recoversAndCancelsBeforeClaimingAndFencesTheSuccess() {
        Fixture fixture = fixture(10);
        when(fixture.mapper().selectReadyDispatchJobIds(NOW, 20))
                .thenReturn(List.of(51L));
        when(fixture.mapper().claimDispatchJob(51L, NOW, NOW.plusSeconds(120)))
                .thenReturn(job(1));
        when(fixture.client().sendMessage("attendance-token", 700L, "message"))
                .thenReturn(900L);
        when(fixture.mapper().markSent(51L, 4L, 900L, NOW)).thenReturn(1);

        fixture.dispatcher().recoverAndDispatchReady();

        InOrder order = inOrder(fixture.mapper());
        order.verify(fixture.mapper()).recoverExpiredDispatchLeases(NOW);
        order.verify(fixture.mapper()).cancelIneligibleOutbox(NOW);
        order.verify(fixture.mapper()).selectReadyDispatchJobIds(NOW, 20);
        order.verify(fixture.mapper()).claimDispatchJob(
                51L, NOW, NOW.plusSeconds(120));
        order.verify(fixture.mapper()).markSent(51L, 4L, 900L, NOW);
    }

    @Test
    void startsExponentialBackoffAtThirtySecondsUsingTheClaimedAttemptCount() {
        Fixture fixture = fixture(10);
        retryableFailure(fixture, job(1), null);

        fixture.dispatcher().recoverAndDispatchReady();

        verify(fixture.mapper()).markRetry(
                51L, 4L, NOW.plusSeconds(30), "TELEGRAM_NETWORK_ERROR", NOW);
    }

    @Test
    void followsTelegramRetryAfterAndCapsOnlyTheInternalBackoffAtOneHour() {
        Fixture retryAfterFixture = fixture(10);
        retryableFailure(retryAfterFixture, job(2), 4_500);

        retryAfterFixture.dispatcher().recoverAndDispatchReady();

        verify(retryAfterFixture.mapper()).markRetry(
                51L, 4L, NOW.plusSeconds(4_500), "TELEGRAM_NETWORK_ERROR", NOW);

        Fixture cappedFixture = fixture(10);
        retryableFailure(cappedFixture, job(9), null);

        cappedFixture.dispatcher().recoverAndDispatchReady();

        verify(cappedFixture.mapper()).markRetry(
                51L, 4L, NOW.plusSeconds(3_600), "TELEGRAM_NETWORK_ERROR", NOW);
    }

    @Test
    void marksDeadOnlyOnTheConfiguredFinalAttempt() {
        Fixture ninth = fixture(10);
        retryableFailure(ninth, job(9), null);
        ninth.dispatcher().recoverAndDispatchReady();
        verify(ninth.mapper(), never()).markDead(
                anyLong(), anyLong(), anyString(), any(Instant.class));

        Fixture tenth = fixture(10);
        retryableFailure(tenth, job(10), null);
        when(tenth.mapper().markDead(
                51L, 4L, "TELEGRAM_NETWORK_ERROR", NOW)).thenReturn(1);

        tenth.dispatcher().recoverAndDispatchReady();

        verify(tenth.mapper()).markDead(
                51L, 4L, "TELEGRAM_NETWORK_ERROR", NOW);
        verify(tenth.mapper(), never()).markRetry(
                anyLong(), anyLong(), any(Instant.class),
                anyString(), any(Instant.class));
    }

    @Test
    void revokesOnlyTheExactConnectionFromAnAcceptedPermanentFailure() {
        Fixture fixture = fixture(10);
        permanentFailure(fixture, job(1));
        when(fixture.mapper().markDead(
                51L, 4L, "TELEGRAM_HTTP_403", NOW)).thenReturn(1);

        fixture.dispatcher().recoverAndDispatchReady();

        verify(fixture.mapper()).deleteConnectionIfUnchanged(
                31L, 700L, CONNECTION_UPDATED_AT);
    }

    @Test
    void stalePermanentFailureCannotDeleteAReconnectedAdministrator() {
        Fixture fixture = fixture(10);
        permanentFailure(fixture, job(1));
        when(fixture.mapper().markDead(
                51L, 4L, "TELEGRAM_HTTP_403", NOW)).thenReturn(0);

        fixture.dispatcher().recoverAndDispatchReady();

        verify(fixture.mapper(), never()).deleteConnectionIfUnchanged(
                anyLong(), anyLong(), any(Instant.class));
    }

    @Test
    void startsEachLeaseAtTheActualClaimTimeInsteadOfTheBatchTime() {
        TelegramNotificationMapper mapper = mock(TelegramNotificationMapper.class);
        TelegramBotClient client = mock(TelegramBotClient.class);
        Clock clock = mock(Clock.class);
        Instant firstClaim = NOW.plusSeconds(10);
        Instant firstSent = NOW.plusSeconds(11);
        Instant secondClaim = NOW.plusSeconds(20);
        Instant secondSent = NOW.plusSeconds(21);
        when(clock.instant()).thenReturn(
                NOW, firstClaim, firstSent, secondClaim, secondSent);
        when(mapper.selectReadyDispatchJobIds(NOW, 20)).thenReturn(List.of(51L, 52L));
        when(mapper.claimDispatchJob(51L, firstClaim, firstClaim.plusSeconds(120)))
                .thenReturn(job(51L, 1));
        when(mapper.claimDispatchJob(52L, secondClaim, secondClaim.plusSeconds(120)))
                .thenReturn(job(52L, 1));
        when(client.sendMessage("attendance-token", 700L, "message"))
                .thenReturn(901L, 902L);
        TelegramNotificationDispatcher dispatcher = new TelegramNotificationDispatcher(
                mapper, client, properties(10), clock);

        dispatcher.recoverAndDispatchReady();

        verify(mapper).claimDispatchJob(
                51L, firstClaim, firstClaim.plusSeconds(120));
        verify(mapper).claimDispatchJob(
                52L, secondClaim, secondClaim.plusSeconds(120));
        verify(mapper).markSent(51L, 4L, 901L, firstSent);
        verify(mapper).markSent(52L, 4L, 902L, secondSent);
    }

    @Test
    void readsTheExactNextDeliveryOrLeaseTimeFromTheOutbox() {
        Fixture fixture = fixture(10);
        Instant actionAt = NOW.plusSeconds(45);
        when(fixture.mapper().selectNextDispatchActionAt()).thenReturn(actionAt);

        assertThat(fixture.dispatcher().findNextActionAt()).isEqualTo(actionAt);
    }

    private static void retryableFailure(
            Fixture fixture, TelegramDispatchJob job, Integer retryAfter) {
        readyClaim(fixture, job);
        when(fixture.client().sendMessage("attendance-token", 700L, "message"))
                .thenThrow(new TelegramDeliveryFailure(
                        false, false, retryAfter, "TELEGRAM_NETWORK_ERROR", null));
    }

    private static void permanentFailure(Fixture fixture, TelegramDispatchJob job) {
        readyClaim(fixture, job);
        when(fixture.client().sendMessage("attendance-token", 700L, "message"))
                .thenThrow(new TelegramDeliveryFailure(
                        true, true, null, "TELEGRAM_HTTP_403", null));
    }

    private static void readyClaim(Fixture fixture, TelegramDispatchJob job) {
        when(fixture.mapper().selectReadyDispatchJobIds(NOW, 20))
                .thenReturn(List.of(job.id()));
        when(fixture.mapper().claimDispatchJob(
                job.id(), NOW, NOW.plusSeconds(120))).thenReturn(job);
    }

    private static Fixture fixture(int maxAttempts) {
        TelegramNotificationMapper mapper = mock(TelegramNotificationMapper.class);
        TelegramBotClient client = mock(TelegramBotClient.class);
        return new Fixture(
                mapper,
                client,
                new TelegramNotificationDispatcher(
                        mapper, client, properties(maxAttempts),
                        Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static TelegramProperties properties(int maxAttempts) {
        return new TelegramProperties(
                true,
                "attendance-token",
                "attendance_bot",
                "webhook-secret",
                "link-token-pepper-at-least-32-bytes",
                maxAttempts,
                30);
    }

    private static TelegramDispatchJob job(int attemptCount) {
        return job(51L, attemptCount);
    }

    private static TelegramDispatchJob job(long id, int attemptCount) {
        return new TelegramDispatchJob(
                id, 31L, 700L, CONNECTION_UPDATED_AT, "message", attemptCount, 4L);
    }

    private record Fixture(
            TelegramNotificationMapper mapper,
            TelegramBotClient client,
            TelegramNotificationDispatcher dispatcher) {
    }
}
