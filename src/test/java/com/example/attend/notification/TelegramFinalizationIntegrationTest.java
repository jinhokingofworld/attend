package com.example.attend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.attend.attendance.application.FinalizeAttendanceDayService;
import com.example.attend.notification.application.TelegramConnectionService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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

    @BeforeEach
    void clear() {
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
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM public.attendance_notification_outbox
                WHERE account_id = ? AND notification_type = 'TELEGRAM_CONNECTION_TEST'
                """, Integer.class, accountId)).isEqualTo(1);

        connectionService.disconnect(accountId);
        assertThat(connectionService.view(accountId).state()).isEqualTo("UNLINKED");
    }

    private long insert(String sql, Object... args) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
        if (id == null) {
            throw new IllegalStateException("fixture insert did not return an ID");
        }
        return id;
    }
}
