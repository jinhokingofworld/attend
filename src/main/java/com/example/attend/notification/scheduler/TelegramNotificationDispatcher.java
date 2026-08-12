package com.example.attend.notification.scheduler;

import com.example.attend.config.TelegramProperties;
import com.example.attend.notification.domain.TelegramDispatchJob;
import com.example.attend.notification.infrastructure.mybatis.TelegramNotificationMapper;
import com.example.attend.notification.infrastructure.telegram.TelegramBotClient;
import com.example.attend.notification.infrastructure.telegram.TelegramDeliveryFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 일반 출석 Telegram outbox를 claim하고 네트워크 호출을 수행한다. */
@Component
@ConditionalOnProperty(name = "attendance.telegram.enabled", havingValue = "true")
public final class TelegramNotificationDispatcher {
    private static final Logger log =
            LoggerFactory.getLogger(TelegramNotificationDispatcher.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final int CLAIM_LIMIT = 20;

    private final TelegramNotificationMapper mapper;
    private final TelegramBotClient client;
    private final TelegramProperties properties;
    private final Clock clock;

    public TelegramNotificationDispatcher(
            TelegramNotificationMapper mapper,
            TelegramBotClient client,
            TelegramProperties properties,
            Clock clock) {
        this.mapper = mapper;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    /** 만료 lease와 권한 회수 행을 정리한 뒤 현재 ready인 한 batch를 처리한다. */
    public void recoverAndDispatchReady() {
        Instant now = clock.instant();
        mapper.recoverExpiredDispatchLeases(now);
        mapper.cancelIneligibleOutbox(now);
        List<Long> outboxIds = mapper.selectReadyDispatchJobIds(now, CLAIM_LIMIT);
        for (long outboxId : outboxIds) {
            dispatchById(outboxId);
        }
    }

    /** 다음 retry 또는 처리 중 lease 만료 중 가장 이른 DB 시각을 반환한다. */
    public Instant findNextActionAt() {
        return mapper.selectNextDispatchActionAt();
    }

    private void dispatchById(long outboxId) {
        // 앞선 Telegram 호출이 느려도 뒤 작업의 lease는 실제 claim 시점부터 시작한다.
        Instant claimedAt = clock.instant();
        TelegramDispatchJob job = mapper.claimDispatchJob(
                outboxId, claimedAt, claimedAt.plus(LEASE_DURATION));
        if (job == null) {
            return;
        }
        try {
            long messageId = client.sendMessage(
                    properties.botToken(), job.chatId(), job.messageText());
            int updated = mapper.markSent(
                    job.id(), job.claimVersion(), messageId, clock.instant());
            if (updated != 1) {
                log.warn(
                        "Ignored stale Telegram delivery success. outboxId={}, claimVersion={}",
                        job.id(), job.claimVersion());
            }
        } catch (TelegramDeliveryFailure exception) {
            fail(job, exception);
        }
    }

    private void fail(TelegramDispatchJob job, TelegramDeliveryFailure exception) {
        Instant now = clock.instant();
        // claim SQL increments attempt_count before returning the job.
        int attempted = job.attemptCount();
        if (exception.permanent() || attempted >= properties.maxAttempts()) {
            int updated = mapper.markDead(
                    job.id(), job.claimVersion(), exception.safeCode(), now);
            if (updated != 1) {
                log.warn(
                        "Ignored stale Telegram delivery failure. outboxId={}, claimVersion={}, code={}",
                        job.id(), job.claimVersion(), exception.safeCode());
                return;
            }
            if (exception.revokeConnection()) {
                mapper.deleteConnectionIfUnchanged(
                        job.accountId(), job.chatId(), job.connectionUpdatedAt());
            }
            return;
        }
        // retry_after is Telegram's authoritative rate-limit window. Shortening
        // it would retry before the server permits; only the local backoff is capped.
        long seconds = exception.retryAfterSeconds() != null
                ? Math.max(1, exception.retryAfterSeconds())
                : Math.min(3600, 30L * (1L << Math.min(7, attempted - 1)));
        int updated = mapper.markRetry(
                job.id(), job.claimVersion(), now.plusSeconds(seconds),
                exception.safeCode(), now);
        if (updated != 1) {
            log.warn(
                    "Ignored stale Telegram delivery failure. outboxId={}, claimVersion={}, code={}",
                    job.id(), job.claimVersion(), exception.safeCode());
        }
    }
}
