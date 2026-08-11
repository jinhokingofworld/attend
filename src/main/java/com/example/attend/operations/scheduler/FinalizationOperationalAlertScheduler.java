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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 영속 운영 이벤트를 별도 Telegram Bot으로 전달한다. */
@Component
@ConditionalOnProperty(
        name = "attendance.operations.telegram.enabled", havingValue = "true")
public final class FinalizationOperationalAlertScheduler {
    private static final Logger log =
            LoggerFactory.getLogger(FinalizationOperationalAlertScheduler.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final int CLAIM_LIMIT = 20;
    private final FinalizationOperationalEventMapper mapper;
    private final FinalizationOperationalAlertFormatter formatter;
    private final TelegramBotClient telegramClient;
    private final OperationalTelegramProperties properties;
    private final Clock clock;

    public FinalizationOperationalAlertScheduler(
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

    @Scheduled(fixedDelayString =
            "${attendance.operations.telegram.dispatch-fixed-delay-ms:10000}")
    public void dispatch() {
        Instant now = clock.instant();
        mapper.recoverExpiredLeases(now);
        for (long eventId : mapper.selectReadyEventIds(now, CLAIM_LIMIT)) {
            FinalizationOperationalAlertJob job = mapper.claimEvent(
                    eventId, now, now.plus(LEASE_DURATION));
            if (job == null) {
                continue;
            }
            deliver(job);
        }
    }

    private void deliver(FinalizationOperationalAlertJob job) {
        try {
            long messageId = telegramClient.sendMessage(
                    properties.botToken(),
                    properties.chatId(),
                    formatter.format(job));
            mapper.markSent(
                    job.id(), job.deliveryClaimVersion(), messageId, clock.instant());
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
        mapper.markRetry(
                job.id(),
                job.deliveryClaimVersion(),
                now.plusSeconds(seconds),
                safeErrorCode,
                now);
        log.warn(
                "Finalization operational alert delivery failed; retry scheduled. eventId={}, code={}",
                job.id(), safeErrorCode);
    }
}
