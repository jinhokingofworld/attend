package com.example.attend.notification.scheduler;

import com.example.attend.config.TelegramProperties;
import com.example.attend.notification.domain.TelegramDispatchJob;
import com.example.attend.notification.infrastructure.mybatis.TelegramNotificationMapper;
import com.example.attend.notification.infrastructure.telegram.TelegramBotClient;
import com.example.attend.notification.infrastructure.telegram.TelegramDeliveryFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** outbox를 claim하고 Telegram 네트워크 호출을 DB 트랜잭션 밖에서 수행한다. */
@Component
@ConditionalOnProperty(name = "attendance.telegram.enabled", havingValue = "true")
public final class TelegramNotificationScheduler {
    private final TelegramNotificationMapper mapper;
    private final TelegramBotClient client;
    private final TelegramProperties properties;
    private final Clock clock;

    public TelegramNotificationScheduler(
            TelegramNotificationMapper mapper,
            TelegramBotClient client,
            TelegramProperties properties,
            Clock clock) {
        this.mapper = mapper;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${attendance.telegram.dispatch-fixed-delay-ms:30000}")
    public void dispatch() {
        Instant now = clock.instant();
        mapper.recoverExpiredDispatchLeases(now);
        mapper.cancelIneligibleOutbox(now);
        for (long outboxId : mapper.selectReadyDispatchJobIds(now, 20)) {
            TelegramDispatchJob job = mapper.claimDispatchJob(
                    outboxId, now, now.plus(Duration.ofMinutes(2)));
            if (job == null) {
                continue;
            }
            try {
                long messageId = client.sendMessage(
                        properties.botToken(), job.chatId(), job.messageText());
                mapper.markSent(job.id(), job.claimVersion(), messageId, clock.instant());
            } catch (TelegramDeliveryFailure exception) {
                fail(job, exception);
            }
        }
    }

    private void fail(TelegramDispatchJob job, TelegramDeliveryFailure exception) {
        Instant now = clock.instant();
        int attempted = job.attemptCount() + 1;
        if (exception.permanent() || attempted >= properties.maxAttempts()) {
            mapper.markDead(job.id(), job.claimVersion(), exception.safeCode(), now);
            if (exception.revokeConnection()) {
                mapper.deleteConnection(job.accountId());
            }
            return;
        }
        long seconds = exception.retryAfterSeconds() != null
                ? exception.retryAfterSeconds()
                : Math.min(3600, 30L * (1L << Math.min(6, attempted - 1)));
        mapper.markRetry(job.id(), job.claimVersion(), now.plusSeconds(seconds), exception.safeCode(), now);
    }
}
