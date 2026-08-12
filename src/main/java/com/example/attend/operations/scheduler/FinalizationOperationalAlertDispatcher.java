package com.example.attend.operations.scheduler;

import com.example.attend.config.OperationalTelegramProperties;
import com.example.attend.notification.infrastructure.telegram.TelegramBotClient;
import com.example.attend.notification.infrastructure.telegram.TelegramDeliveryFailure;
import com.example.attend.operations.application.FinalizationOperationalAlertFormatter;
import com.example.attend.operations.domain.FinalizationOperationalAlertJob;
import com.example.attend.operations.infrastructure.mybatis.FinalizationOperationalEventMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 운영 알림 outbox를 claim하고 별도 Telegram Bot으로 전달한다. */
@Component
@ConditionalOnProperty(
        name = "attendance.operations.telegram.enabled", havingValue = "true")
public final class FinalizationOperationalAlertDispatcher {
    private static final Logger log =
            LoggerFactory.getLogger(FinalizationOperationalAlertDispatcher.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final int CLAIM_LIMIT = 20;

    private final FinalizationOperationalEventMapper mapper;
    private final FinalizationOperationalAlertFormatter formatter;
    private final TelegramBotClient telegramClient;
    private final OperationalTelegramProperties properties;
    private final Clock clock;

    public FinalizationOperationalAlertDispatcher(
            FinalizationOperationalEventMapper mapper,
            FinalizationOperationalAlertFormatter formatter,
            TelegramBotClient telegramClient,
            OperationalTelegramProperties properties,
            Clock clock) {
        this.mapper = mapper;
        this.formatter = formatter;
        this.telegramClient = telegramClient;
        this.properties = properties;
        this.clock = clock;
    }

    /** 커밋 직후 전달을 위해 지정된 outbox 한 건만 claim한다. */
    public void dispatchById(long eventId) {
        Instant now = clock.instant();
        FinalizationOperationalAlertJob job = mapper.claimEvent(
                eventId, now, now.plus(LEASE_DURATION));
        if (job != null) {
            deliver(job);
        }
    }

    /** 만료 lease를 복구한 뒤 현재 전송 가능한 outbox를 한 batch 처리한다. */
    public void recoverAndDispatchReady() {
        Instant now = clock.instant();
        mapper.recoverExpiredLeases(now);
        List<Long> eventIds = mapper.selectReadyEventIds(now, CLAIM_LIMIT);
        for (long eventId : eventIds) {
            dispatchById(eventId);
        }
    }

    /** 다음 retry 또는 처리 중 lease 만료 중 가장 이른 DB 시각을 반환한다. */
    public Instant findNextActionAt() {
        return mapper.selectNextDeliveryActionAt();
    }

    private void deliver(FinalizationOperationalAlertJob job) {
        try {
            long messageId = telegramClient.sendMessage(
                    properties.botToken(),
                    properties.chatId(),
                    formatter.format(job));
            int updated = mapper.markSent(
                    job.id(), job.deliveryClaimVersion(), messageId, clock.instant());
            if (updated != 1) {
                log.warn(
                        "Ignored stale operational alert success. eventId={}, claimVersion={}",
                        job.id(), job.deliveryClaimVersion());
            }
        } catch (TelegramDeliveryFailure exception) {
            retry(job, exception.safeCode(), exception.retryAfterSeconds());
        } catch (RuntimeException exception) {
            retry(job, "OPERATIONS_ALERT_INTERNAL_ERROR", null);
        }
    }

    private void retry(
            FinalizationOperationalAlertJob job,
            String safeErrorCode,
            Integer retryAfterSeconds) {
        Instant now = clock.instant();
        long seconds = retryAfterSeconds != null
                ? Math.max(1, retryAfterSeconds)
                : Math.min(3600,
                        30L * (1L << Math.min(7, job.deliveryAttemptCount() - 1)));
        int updated = mapper.markRetry(
                job.id(),
                job.deliveryClaimVersion(),
                now.plusSeconds(seconds),
                safeErrorCode,
                now);
        if (updated != 1) {
            log.warn(
                    "Ignored stale operational alert failure. eventId={}, claimVersion={}, code={}",
                    job.id(), job.deliveryClaimVersion(), safeErrorCode);
            return;
        }
        log.warn(
                "Finalization operational alert delivery failed; retry scheduled. eventId={}, code={}",
                job.id(), safeErrorCode);
    }
}
