package com.example.attend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.example.attend.attendance.application.FinalizeAttendanceDayService;
import com.example.attend.notification.application.TelegramConnectionService;
import com.example.attend.notification.domain.TelegramDispatchJob;
import com.example.attend.notification.infrastructure.mybatis.TelegramNotificationMapper;
import com.example.attend.notification.scheduler.TelegramNotificationDispatcher;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 마감 transaction이 Telegram outbox snapshot을 함께 남기는지 실제 DB로 검증한다. */
@SpringBootTest(properties = {
        "attendance.admin.write-enabled=true",
        "attendance.telegram.enabled=true",
        "attendance.telegram.bot-token=test-bot-token",
        "attendance.telegram.bot-username=attend_test_bot",
        "attendance.telegram.webhook-secret=test-webhook-secret",
        "attendance.telegram.link-token-pepper=test-link-token-pepper-that-is-at-least-32-bytes"
})
@ActiveProfiles("test")
@Testcontainers
class TelegramFinalizationIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FinalizeAttendanceDayService finalizationService;

    @Autowired
    private TelegramConnectionService connectionService;

    @Autowired
    private TelegramNotificationMapper notificationMapper;

    @MockitoBean
    private TelegramNotificationDispatcher telegramNotificationDispatcher;

    @BeforeEach
    void clear() {
        reset(telegramNotificationDispatcher);
        jdbcTemplate.update("DELETE FROM public.attendance_notification_outbox");
        jdbcTemplate.update("DELETE FROM public.account_telegram_connection");
        jdbcTemplate.update("DELETE FROM public.telegram_link_token");
        jdbcTemplate.update("DELETE FROM public.telegram_webhook_update");
        jdbcTemplate.update("DELETE FROM public.audit_log");
        jdbcTemplate.update("DELETE FROM public.attendance_record");
        jdbcTemplate.update("DELETE FROM public.attendance_target");
        jdbcTemplate.update("DELETE FROM public.attendance_day");
        jdbcTemplate.update("DELETE FROM public.attendance_band");
        jdbcTemplate.update("DELETE FROM public.attendance_policy_version");
        jdbcTemplate.update("DELETE FROM public.account_department_role");
        jdbcTemplate.update("DELETE FROM public.account");
        jdbcTemplate.update("DELETE FROM public.department");
    }

    @Test
    void finalizationCreatesOneSanitizedOutboxMessageForEachConnectedDepartmentAdmin() {
        long departmentId = insert("INSERT INTO public.department(name) VALUES ('유치부') RETURNING id");
        long accountId = insert("""
                INSERT INTO public.account(username, password_hash, status, password_changed_at)
                VALUES ('telegram-admin', 'test-password-hash', 'ACTIVE', CURRENT_TIMESTAMP)
                RETURNING id
                """);
        jdbcTemplate.update("""
                INSERT INTO public.account_department_role(account_id, department_id, role)
                VALUES (?, ?, 'DEPARTMENT_ADMIN')
                """, accountId, departmentId);
        jdbcTemplate.update("""
                INSERT INTO public.account_telegram_connection(account_id, chat_id, telegram_user_id)
                VALUES (?, 123456789, 987654321)
                """, accountId);
        long secondAccountId = insert("""
                INSERT INTO public.account(username, password_hash, status, password_changed_at)
                VALUES ('telegram-admin-2', 'test-password-hash', 'ACTIVE', CURRENT_TIMESTAMP)
                RETURNING id
                """);
        jdbcTemplate.update("""
                INSERT INTO public.account_department_role(account_id, department_id, role)
                VALUES (?, ?, 'DEPARTMENT_ADMIN')
                """, secondAccountId, departmentId);
        jdbcTemplate.update("""
                INSERT INTO public.account_telegram_connection(account_id, chat_id, telegram_user_id)
                VALUES (?, 123456790, 987654322)
                """, secondAccountId);
        long policyId = insert("""
                INSERT INTO public.attendance_policy_version(
                    department_id, version_no, name, check_in_start_time, status,
                    created_by_account_id, published_by_account_id, published_at)
                VALUES (?, 1, '기본 정책', TIME '08:30', 'PUBLISHED', ?, ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, departmentId, accountId, accountId);
        jdbcTemplate.update("""
                INSERT INTO public.attendance_band(policy_version_id, sequence_no, label, parent_status, upper_time)
                VALUES (?, 1, '정상', 'PRESENT', TIME '09:00'), (?, 2, '지각', 'LATE', TIME '09:30')
                """, policyId, policyId);
        long dayId = insert("""
                INSERT INTO public.attendance_day(
                    department_id, attendance_date, policy_version_id, finalization_due_at,
                    status, created_by_account_id)
                VALUES (?, DATE '2026-08-01', ?, ?, 'SCHEDULED', ?)
                RETURNING id
				""", departmentId, policyId, OffsetDateTime.parse("2026-08-01T00:30:00Z"), accountId);

        assertThat(finalizationService.finalizeDay(dayId)).isZero();

        String message = jdbcTemplate.queryForObject("""
                SELECT message_text FROM public.attendance_notification_outbox
                WHERE attendance_day_id = ? AND account_id = ?
                """, String.class, dayId, accountId);
        assertThat(message).contains("출석 마감 완료", "부서: 유치부", "지각\n• 없음", "결석\n• 없음");
        assertThat(message).doesNotContain("123456789", "987654321", "test-password-hash");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.attendance_notification_outbox
                WHERE attendance_day_id = ?
                """, Integer.class, dayId)).isEqualTo(2);
        verify(telegramNotificationDispatcher, timeout(2_000))
                .recoverAndDispatchReady();
    }

    @Test
    void accountCanCreateConsumeAndRemoveItsOwnTelegramConnection() {
        long accountId = insert("""
                INSERT INTO public.account(username, password_hash, status, password_changed_at)
                VALUES ('telegram-link-admin', 'test-password-hash', 'ACTIVE', CURRENT_TIMESTAMP)
                RETURNING id
                """);

        String link = connectionService.issueLink(accountId);
        String rawToken = link.substring(link.indexOf("?start=") + "?start=".length());
        assertThat(connectionService.view(accountId).state()).isEqualTo("LINK_PENDING");
        assertThat(connectionService.consumeStart(1001L, rawToken, 4444L, 5555L)).isTrue();
        assertThat(connectionService.view(accountId).state()).isEqualTo("LINKED");

        connectionService.requestTestMessage(accountId);
        verify(telegramNotificationDispatcher, timeout(2_000))
                .recoverAndDispatchReady();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.attendance_notification_outbox
                WHERE account_id = ? AND notification_type = 'TELEGRAM_CONNECTION_TEST'
                """, Integer.class, accountId)).isEqualTo(1);

        connectionService.disconnect(accountId);
        assertThat(connectionService.view(accountId).state()).isEqualTo("UNLINKED");
    }

    @Test
    void invalidStartDoesNotCreateAnUnboundedWebhookDeduplicationRow() {
        assertThat(connectionService.consumeStart(
                7001L, "not-a-issued-token", 4444L, 5555L)).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.telegram_webhook_update", Integer.class)).isZero();
    }

    @Test
    void expiredClaimCannotOverwriteTheNewerClaimResult() {
        long accountId = insertActiveConnectedAccount("telegram-fencing-admin");
        notificationMapper.insertTestOutbox(accountId, "fencing test");
        long outboxId = insert("""
                SELECT id FROM public.attendance_notification_outbox
                WHERE account_id = ?
                """, accountId);
        Instant firstClaimAt = Instant.now().plusSeconds(1);
        TelegramDispatchJob first = notificationMapper.claimDispatchJob(
                outboxId, firstClaimAt, firstClaimAt.plusSeconds(1));
        assertThat(first).isNotNull();

        Instant recoveryAt = firstClaimAt.plusSeconds(3);
        notificationMapper.recoverExpiredDispatchLeases(recoveryAt);
        TelegramDispatchJob second = notificationMapper.claimDispatchJob(
                outboxId, recoveryAt, recoveryAt.plusSeconds(30));
        assertThat(second).isNotNull();
        assertThat(second.claimVersion()).isGreaterThan(first.claimVersion());

        assertThat(notificationMapper.markDead(
                first.id(), first.claimVersion(), "STALE", recoveryAt)).isZero();
        assertThat(notificationMapper.markSent(
                second.id(), second.claimVersion(), 17L, recoveryAt)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM public.attendance_notification_outbox WHERE id = ?
                """, String.class, outboxId)).isEqualTo("SENT");
    }

    @Test
    void claimRechecksEligibilityInsteadOfUsingAStaleBatchRecipient() {
        long accountId = insertActiveConnectedAccount("telegram-revoked-admin");
        notificationMapper.insertTestOutbox(accountId, "recipient check");
        long outboxId = insert("""
                SELECT id FROM public.attendance_notification_outbox
                WHERE account_id = ?
                """, accountId);
        jdbcTemplate.update("UPDATE public.account SET status = 'DISABLED' WHERE id = ?", accountId);

        assertThat(notificationMapper.claimDispatchJob(
                outboxId, Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:02:00Z"))).isNull();
    }

    @Test
    void selectsTheGlobalNextRetryOrLeaseAndRecoversAtTheInclusiveBoundary() {
        long accountId = insertActiveConnectedAccount("telegram-next-action-admin");
        Instant pendingAt = Instant.parse("2026-08-12T01:30:00Z");
        Instant retryAt = Instant.parse("2026-08-12T01:20:00Z");
        Instant leaseAt = Instant.parse("2026-08-12T01:10:00Z");
        jdbcTemplate.update("""
                INSERT INTO public.attendance_notification_outbox(
                    notification_type, account_id, message_text, status,
                    attempt_count, claim_version, next_attempt_at, lease_until,
                    telegram_message_id, sent_at)
                VALUES
                    ('TELEGRAM_CONNECTION_TEST', ?, 'pending', 'PENDING',
                     0, 0, ?, NULL, NULL, NULL),
                    ('TELEGRAM_CONNECTION_TEST', ?, 'retry', 'RETRY',
                     1, 1, ?, NULL, NULL, NULL),
                    ('TELEGRAM_CONNECTION_TEST', ?, 'processing', 'PROCESSING',
                     1, 3, ?, ?, NULL, NULL),
                    ('TELEGRAM_CONNECTION_TEST', ?, 'sent', 'SENT',
                     1, 1, ?, NULL, 901, ?)
                """,
                accountId, pendingAt.atOffset(ZoneOffset.UTC),
                accountId, retryAt.atOffset(ZoneOffset.UTC),
                accountId, OffsetDateTime.parse("2026-08-12T01:00:00Z"),
                leaseAt.atOffset(ZoneOffset.UTC),
                accountId, OffsetDateTime.parse("2026-08-12T01:01:00Z"),
                OffsetDateTime.parse("2026-08-12T01:01:00Z"));

        assertThat(notificationMapper.selectNextDispatchActionAt()).isEqualTo(leaseAt);
        assertThat(notificationMapper.recoverExpiredDispatchLeases(leaseAt)).isEqualTo(1);
        assertThat(notificationMapper.selectNextDispatchActionAt()).isEqualTo(leaseAt);
        assertThat(notificationMapper.selectReadyDispatchJobIds(leaseAt, 20)).hasSize(1);
    }

    @Test
    void anOldPermanentResultCannotDeleteAConnectionThatWasRelinked() {
        long accountId = insertActiveConnectedAccount("telegram-relinked-admin");
        notificationMapper.insertTestOutbox(accountId, "relink fencing");
        long outboxId = insert("""
                SELECT id FROM public.attendance_notification_outbox
                WHERE account_id = ?
                """, accountId);
        Instant claimedAt = Instant.parse("2030-08-12T01:00:00Z");
        TelegramDispatchJob job = notificationMapper.claimDispatchJob(
                outboxId, claimedAt, claimedAt.plusSeconds(120));
        assertThat(job).isNotNull();
        Instant relinkedAt = job.connectionUpdatedAt().plusSeconds(1);
        jdbcTemplate.update("""
                UPDATE public.account_telegram_connection
                SET updated_at = ?
                WHERE account_id = ?
                """, relinkedAt.atOffset(ZoneOffset.UTC), accountId);

        assertThat(notificationMapper.deleteConnectionIfUnchanged(
                job.accountId(), job.chatId(), job.connectionUpdatedAt())).isZero();
        assertThat(notificationMapper.selectConnection(accountId)).isNotNull();
    }

    @Test
    void aPermanentResultCanDeleteTheExactConnectionItClaimed() {
        long accountId = insertActiveConnectedAccount("telegram-unchanged-admin");
        notificationMapper.insertTestOutbox(accountId, "unchanged connection");
        long outboxId = insert("""
                SELECT id FROM public.attendance_notification_outbox
                WHERE account_id = ?
                """, accountId);
        Instant claimedAt = Instant.parse("2030-08-12T01:00:00Z");
        TelegramDispatchJob job = notificationMapper.claimDispatchJob(
                outboxId, claimedAt, claimedAt.plusSeconds(120));
        assertThat(job).isNotNull();

        assertThat(notificationMapper.deleteConnectionIfUnchanged(
                job.accountId(), job.chatId(), job.connectionUpdatedAt())).isEqualTo(1);
        assertThat(notificationMapper.selectConnection(accountId)).isNull();
    }

    @Test
    void startupRecoveryAlsoRepairsALegacyProcessingRowWithoutALease() {
        long accountId = insertActiveConnectedAccount("telegram-null-lease-admin");
        notificationMapper.insertTestOutbox(accountId, "legacy null lease");
        Instant originalAttemptAt = Instant.parse("2030-08-12T01:00:00Z");
        jdbcTemplate.update("""
                UPDATE public.attendance_notification_outbox
                SET status = 'PROCESSING', attempt_count = 1, claim_version = 1,
                    next_attempt_at = ?, lease_until = NULL
                WHERE account_id = ?
                """, originalAttemptAt.atOffset(ZoneOffset.UTC), accountId);

        assertThat(notificationMapper.selectNextDispatchActionAt())
                .isEqualTo(originalAttemptAt);
        Instant recoveredAt = Instant.parse("2026-08-12T01:00:00Z");
        assertThat(notificationMapper.recoverExpiredDispatchLeases(recoveredAt))
                .isEqualTo(1);
        assertThat(notificationMapper.selectNextDispatchActionAt())
                .isEqualTo(recoveredAt);
    }

    private long insertActiveConnectedAccount(String username) {
        long accountId = insert("""
                INSERT INTO public.account(username, password_hash, status, password_changed_at)
                VALUES (?, 'test-password-hash', 'ACTIVE', CURRENT_TIMESTAMP)
                RETURNING id
                """, username);
        jdbcTemplate.update("""
                INSERT INTO public.account_telegram_connection(account_id, chat_id, telegram_user_id)
                VALUES (?, ?, ?)
                """, accountId, 100000L + accountId, 200000L + accountId);
        return accountId;
    }

    private long insert(String sql, Object... args) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
        if (id == null) {
            throw new IllegalStateException("fixture insert did not return an ID");
        }
        return id;
    }
}
