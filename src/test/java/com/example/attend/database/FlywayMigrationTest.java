package com.example.attend.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.example.attend.database.DatabaseMigrationRunner.ApprovedSourceClass.LEGACY_OPERATIONAL;
import static com.example.attend.database.DatabaseMigrationRunner.ApprovedSourceClass.NEW_OR_SAMPLE;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.FRESH;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.LEGACY_CANDIDATE;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.REJECTED;

@Testcontainers
class FlywayMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Test
    void migratesFreshDatabaseToTheCompleteTargetSchema() throws Exception {
        Database database = createDatabase("fresh");
        DatabasePreflightInspector inspector =
                new DatabasePreflightInspector();
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner();

        assertThat(inspector.inspect(database.dataSource()).status())
                .isEqualTo(FRESH);

        assertThatThrownBy(() -> runner.migrate(
                database.dataSource(),
                LEGACY_OPERATIONAL
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW_OR_SAMPLE");
        try (Connection connection = database.connect()) {
            assertThat(queryString(connection, """
                    SELECT to_regclass('public.flyway_schema_history')::text
                    """)).isNull();
        }

        runner.migrate(database.dataSource(), NEW_OR_SAMPLE);
        runner.migrate(database.dataSource(), NEW_OR_SAMPLE);

        try (Connection connection = database.connect()) {
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM public.flyway_schema_history
                    WHERE success
                    """)).isEqualTo(8);

            assertThat(queryStrings(connection, """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_type = 'BASE TABLE'
                    """)).contains(
                    "member",
                    "department",
                    "account",
                    "account_credential_token",
                    "account_department_role",
                    "department_membership",
                    "nfc_card",
                    "nfc_card_assignment",
                    "device",
                    "attendance_policy_version",
                    "attendance_band",
                    "attendance_day",
                    "attendance_target",
                    "attendance_record",
                    "tag_event_log",
                    "audit_log"
            );

            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM pg_trigger
                    WHERE NOT tgisinternal
                      AND tgname IN (
                        'trg_department_updated_at',
                        'trg_account_updated_at',
                        'trg_member_updated_at',
                        'trg_nfc_card_updated_at',
                        'trg_device_updated_at',
                        'trg_attendance_record_updated_at'
                      )
                    """)).isEqualTo(6);
        }
    }

    @Test
    void enforcesPendingAccountAndOneTimeInvitationTokenConstraints() throws Exception {
        Database database = createDatabase("token");
        flyway(database).migrate();

        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            long issuerId = queryLong(statement, """
                    INSERT INTO public.account (
                        username,
                        password_hash,
                        system_role,
                        status,
                        password_changed_at
                    )
                    VALUES (
                        'system-admin',
                        '$2a$12$test-only-hash',
                        'SYSTEM_ADMIN',
                        'ACTIVE',
                        CURRENT_TIMESTAMP
                    )
                    RETURNING id
                    """);
            long invitedAccountId = queryLong(statement, """
                    INSERT INTO public.account (username)
                    VALUES ('invited-admin')
                    RETURNING id
                    """);

            assertThat(queryString(statement, """
                    SELECT status
                    FROM public.account
                    WHERE id = %d
                    """.formatted(invitedAccountId))).isEqualTo("PENDING_SETUP");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account (
                        username,
                        password_hash,
                        status
                    )
                    VALUES (
                        'invalid-pending',
                        '$2a$12$must-not-exist',
                        'PENDING_SETUP'
                    )
                    """, "23514", "ck_account_setup_state");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account (
                        username,
                        password_hash,
                        status
                    )
                    VALUES (
                        'invalid-disabled',
                        '$2a$12$must-not-exist',
                        'DISABLED'
                    )
                    """, "23514", "ck_account_setup_state");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        %d,
                        'SIGNUP',
                        '%s',
                        %d,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                    )
                    """.formatted(
                            invitedAccountId,
                            "1".repeat(64),
                            issuerId
                    ), "23514", "ck_account_credential_token_purpose");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        %d,
                        'RESET',
                        '%s',
                        %d,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                    )
                    """.formatted(
                            invitedAccountId,
                            "A".repeat(64),
                            issuerId
                    ), "23514", "ck_account_credential_token_hash");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        999999999,
                        'RESET',
                        '%s',
                        %d,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                    )
                    """.formatted(
                            "2".repeat(64),
                            issuerId
                    ), "23503", "fk_account_credential_token_account");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        %d,
                        'RESET',
                        '%s',
                        999999999,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                    )
                    """.formatted(
                            invitedAccountId,
                            "3".repeat(64)
                    ), "23503", "fk_account_credential_token_issuer");

            statement.executeUpdate("""
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        %d,
                        'INVITATION',
                        '%s',
                        %d,
                        CURRENT_TIMESTAMP + INTERVAL '30 minutes'
                    )
                    """.formatted(invitedAccountId, "a".repeat(64), issuerId));

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        %d,
                        'INVITATION',
                        '%s',
                        %d,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                    )
                    """.formatted(
                            invitedAccountId,
                            "b".repeat(64),
                            issuerId
                    ), "23505", "uq_account_credential_token_active");

            statement.executeUpdate("""
                    UPDATE public.account_credential_token
                    SET revoked_at = CURRENT_TIMESTAMP
                    WHERE account_id = %d
                      AND purpose = 'INVITATION'
                      AND revoked_at IS NULL
                    """.formatted(invitedAccountId));

            statement.executeUpdate("""
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        %d,
                        'INVITATION',
                        '%s',
                        %d,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                    )
                    """.formatted(invitedAccountId, "b".repeat(64), issuerId));

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        %d,
                        'RESET',
                        '%s',
                        %d,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                    )
                    """.formatted(
                            invitedAccountId,
                            "a".repeat(64),
                            issuerId
                    ), "23505", "uq_account_credential_token_hash");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        expires_at
                    )
                    VALUES (
                        %d,
                        'RESET',
                        '%s',
                        %d,
                        CURRENT_TIMESTAMP + INTERVAL '31 minutes'
                    )
                    """.formatted(
                            invitedAccountId,
                            "c".repeat(64),
                            issuerId
                    ), "23514", "ck_account_credential_token_expiry");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        issued_at,
                        expires_at,
                        consumed_at
                    )
                    VALUES (
                        %d,
                        'RESET',
                        '%s',
                        %d,
                        TIMESTAMPTZ '2026-08-01 00:00:00+00',
                        TIMESTAMPTZ '2026-08-01 00:10:00+00',
                        TIMESTAMPTZ '2026-08-01 00:11:00+00'
                    )
                    """.formatted(
                            invitedAccountId,
                            "d".repeat(64),
                            issuerId
                    ), "23514", "ck_account_credential_token_consumed");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        issued_at,
                        expires_at,
                        revoked_at
                    )
                    VALUES (
                        %d,
                        'RESET',
                        '%s',
                        %d,
                        TIMESTAMPTZ '2026-08-01 00:00:00+00',
                        TIMESTAMPTZ '2026-08-01 00:10:00+00',
                        TIMESTAMPTZ '2026-07-31 23:59:00+00'
                    )
                    """.formatted(
                            invitedAccountId,
                            "e".repeat(64),
                            issuerId
                    ), "23514", "ck_account_credential_token_revoked");

            assertConstraintViolation(statement, """
                    INSERT INTO public.account_credential_token (
                        account_id,
                        purpose,
                        token_hash,
                        issued_by_account_id,
                        issued_at,
                        expires_at,
                        consumed_at,
                        revoked_at
                    )
                    VALUES (
                        %d,
                        'RESET',
                        '%s',
                        %d,
                        TIMESTAMPTZ '2026-08-01 00:00:00+00',
                        TIMESTAMPTZ '2026-08-01 00:10:00+00',
                        TIMESTAMPTZ '2026-08-01 00:05:00+00',
                        TIMESTAMPTZ '2026-08-01 00:06:00+00'
                    )
                    """.formatted(
                            invitedAccountId,
                            "f".repeat(64),
                            issuerId
                    ), "23514", "ck_account_credential_token_terminal");

            assertConstraintViolation(statement, """
                    UPDATE public.account
                    SET status = 'ACTIVE'
                    WHERE id = %d
                    """.formatted(invitedAccountId),
                    "23514",
                    "ck_account_setup_state"
            );

            statement.executeUpdate("""
                    UPDATE public.account_credential_token
                    SET consumed_at = CURRENT_TIMESTAMP
                    WHERE account_id = %d
                      AND purpose = 'INVITATION'
                      AND token_hash = '%s'
                    """.formatted(invitedAccountId, "b".repeat(64)));

            statement.executeUpdate("""
                    UPDATE public.account
                    SET password_hash = '$2a$12$test-only-invited-hash',
                        password_changed_at = CURRENT_TIMESTAMP,
                        status = 'ACTIVE'
                    WHERE id = %d
                    """.formatted(invitedAccountId));

            assertThat(queryString(statement, """
                    SELECT status
                    FROM public.account
                    WHERE id = %d
                    """.formatted(invitedAccountId))).isEqualTo("ACTIVE");
        }
    }

    @Test
    void enforcesDepartmentScopeAndActiveHistoryUniqueness() throws Exception {
        Database database = createDatabase("scope");
        flyway(database).migrate();

        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            long departmentA = queryLong(statement, """
                    INSERT INTO public.department (name)
                    VALUES ('아동부')
                    RETURNING id
                    """);
            long departmentB = queryLong(statement, """
                    INSERT INTO public.department (name)
                    VALUES ('청소년부')
                    RETURNING id
                    """);
            long administrator = queryLong(statement, """
                    INSERT INTO public.account (
                        username,
                        password_hash,
                        system_role,
                        status,
                        password_changed_at
                    )
                    VALUES (
                        'scope-admin',
                        '$2a$12$test-only-hash',
                        'SYSTEM_ADMIN',
                        'ACTIVE',
                        CURRENT_TIMESTAMP
                    )
                    RETURNING id
                    """);
            long member = queryLong(statement, """
                    INSERT INTO public.member (name, active)
                    VALUES ('소속 교사', TRUE)
                    RETURNING id
                    """);
            long membership = queryLong(statement, """
                    INSERT INTO public.department_membership (
                        department_id,
                        member_id,
                        created_by_account_id
                    )
                    VALUES (%d, %d, %d)
                    RETURNING id
                    """.formatted(departmentA, member, administrator));

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO public.department_membership (
                        department_id,
                        member_id,
                        created_by_account_id
                    )
                    VALUES (%d, %d, %d)
                    """.formatted(departmentB, member, administrator)))
                    .isInstanceOf(SQLException.class);

            statement.executeUpdate("""
                    INSERT INTO public.account_department_role (
                        account_id,
                        department_id,
                        role,
                        assigned_by_account_id
                    )
                    VALUES (%d, %d, 'DEPARTMENT_ADMIN', %d)
                    """.formatted(administrator, departmentA, administrator));

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO public.account_department_role (
                        account_id,
                        department_id,
                        role,
                        assigned_by_account_id
                    )
                    VALUES (%d, %d, 'DEPARTMENT_ADMIN', %d)
                    """.formatted(administrator, departmentA, administrator)))
                    .isInstanceOf(SQLException.class);

            long firstCard = queryLong(statement, """
                    INSERT INTO public.nfc_card (uid)
                    VALUES ('A1B2C3D4')
                    RETURNING id
                    """);
            long secondCard = queryLong(statement, """
                    INSERT INTO public.nfc_card (uid)
                    VALUES ('B1C2D3E4')
                    RETURNING id
                    """);

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO public.nfc_card_assignment (
                        nfc_card_id,
                        department_id,
                        membership_id,
                        member_id,
                        assigned_by_account_id
                    )
                    VALUES (%d, %d, %d, %d, %d)
                    """.formatted(
                            firstCard,
                            departmentB,
                            membership,
                            member,
                            administrator
                    )))
                    .isInstanceOf(SQLException.class);

            statement.executeUpdate("""
                    INSERT INTO public.nfc_card_assignment (
                        nfc_card_id,
                        department_id,
                        membership_id,
                        member_id,
                        assigned_by_account_id
                    )
                    VALUES (%d, %d, %d, %d, %d)
                    """.formatted(
                            firstCard,
                            departmentA,
                            membership,
                            member,
                            administrator
                    ));

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO public.nfc_card_assignment (
                        nfc_card_id,
                        department_id,
                        membership_id,
                        member_id,
                        assigned_by_account_id
                    )
                    VALUES (%d, %d, %d, %d, %d)
                    """.formatted(
                            secondCard,
                            departmentA,
                            membership,
                            member,
                            administrator
                    )))
                    .isInstanceOf(SQLException.class);

            long policy = queryLong(statement, """
                    INSERT INTO public.attendance_policy_version (
                        department_id,
                        version_no,
                        name,
                        check_in_start_time,
                        created_by_account_id
                    )
                    VALUES (%d, 1, '기본 정책', TIME '08:30', %d)
                    RETURNING id
                    """.formatted(departmentA, administrator));
            long otherPolicy = queryLong(statement, """
                    INSERT INTO public.attendance_policy_version (
                        department_id,
                        version_no,
                        name,
                        check_in_start_time,
                        created_by_account_id
                    )
                    VALUES (%d, 1, '다른 부서 정책', TIME '08:30', %d)
                    RETURNING id
                    """.formatted(departmentB, administrator));

            assertConstraintViolation(statement, """
                    INSERT INTO public.attendance_day (
                        department_id,
                        attendance_date,
                        policy_version_id,
                        created_by_account_id
                    )
                    VALUES (%d, DATE '2026-08-02', %d, %d)
                    """.formatted(
                            departmentB,
                            policy,
                            administrator
                    ), "23503", "fk_attendance_day_policy");

            long band = queryLong(statement, """
                    INSERT INTO public.attendance_band (
                        policy_version_id,
                        sequence_no,
                        label,
                        parent_status,
                        upper_time
                    )
                    VALUES (
                        %d,
                        1,
                        '정상 출석',
                        'PRESENT',
                        TIME '09:00'
                    )
                    RETURNING id
                    """.formatted(policy));
            long otherBand = queryLong(statement, """
                    INSERT INTO public.attendance_band (
                        policy_version_id,
                        sequence_no,
                        label,
                        parent_status,
                        upper_time
                    )
                    VALUES (
                        %d,
                        1,
                        '다른 부서 지각',
                        'LATE',
                        TIME '09:30'
                    )
                    RETURNING id
                    """.formatted(otherPolicy));
            long attendanceDay = queryLong(statement, """
                    INSERT INTO public.attendance_day (
                        department_id,
                        attendance_date,
                        policy_version_id,
                        created_by_account_id
                    )
                    VALUES (%d, DATE '2026-08-02', %d, %d)
                    RETURNING id
                    """.formatted(departmentA, policy, administrator));
            statement.executeUpdate("""
                    INSERT INTO public.attendance_target (
                        attendance_day_id,
                        member_id,
                        department_id,
                        membership_id
                    )
                    VALUES (%d, %d, %d, %d)
                    """.formatted(
                            attendanceDay,
                            member,
                            departmentA,
                            membership
                    ));

            assertConstraintViolation(statement, """
                    INSERT INTO public.attendance_record (
                        attendance_day_id,
                        policy_version_id,
                        member_id,
                        attendance_band_id,
                        status,
                        band_sequence_snapshot,
                        band_label_snapshot,
                        checked_in_at,
                        source
                    )
                    VALUES (
                        %d,
                        %d,
                        %d,
                        %d,
                        'LATE',
                        1,
                        '다른 부서 지각',
                        TIMESTAMPTZ '2026-08-02 00:10:00+00',
                        'NFC'
                    )
                    """.formatted(
                            attendanceDay,
                            otherPolicy,
                            member,
                            otherBand
                    ), "23503", "fk_record_day_policy");

            assertConstraintViolation(statement, """
                    INSERT INTO public.attendance_record (
                        attendance_day_id,
                        policy_version_id,
                        member_id,
                        attendance_band_id,
                        status,
                        band_sequence_snapshot,
                        band_label_snapshot,
                        checked_in_at,
                        source
                    )
                    VALUES (
                        %d,
                        %d,
                        %d,
                        %d,
                        'LATE',
                        1,
                        '다른 부서 지각',
                        TIMESTAMPTZ '2026-08-02 00:10:00+00',
                        'NFC'
                    )
                    """.formatted(
                            attendanceDay,
                            policy,
                            member,
                            otherBand
                    ), "23503", "fk_record_band_policy_status");

            assertConstraintViolation(statement, """
                    INSERT INTO public.attendance_record (
                        attendance_day_id,
                        policy_version_id,
                        member_id,
                        attendance_band_id,
                        status,
                        band_sequence_snapshot,
                        band_label_snapshot,
                        checked_in_at,
                        source
                    )
                    VALUES (
                        %d,
                        %d,
                        %d,
                        %d,
                        'LATE',
                        1,
                        '정상 출석',
                        TIMESTAMPTZ '2026-08-02 00:10:00+00',
                        'NFC'
                    )
                    """.formatted(
                            attendanceDay,
                            policy,
                            member,
                            band
                    ), "23503", "fk_record_band_policy_status");

            assertConstraintViolation(statement, """
                    INSERT INTO public.attendance_record (
                        attendance_day_id,
                        policy_version_id,
                        member_id,
                        attendance_band_id,
                        status,
                        band_sequence_snapshot,
                        band_label_snapshot,
                        checked_in_at,
                        source
                    )
                    VALUES (
                        %d,
                        %d,
                        %d,
                        %d,
                        'PRESENT',
                        1,
                        '정상 출석',
                        TIMESTAMPTZ '2026-08-02 00:00:00+00',
                        'AUTO_ABSENCE'
                    )
                    """.formatted(
                            attendanceDay,
                            policy,
                            member,
                            band
                    ), "23514", "ck_record_source_status");

            assertConstraintViolation(statement, """
                    INSERT INTO public.attendance_record (
                        attendance_day_id,
                        policy_version_id,
                        member_id,
                        status,
                        source
                    )
                    VALUES (
                        %d,
                        %d,
                        %d,
                        'ABSENT',
                        'NFC'
                    )
                    """.formatted(
                            attendanceDay,
                            policy,
                            member
                    ), "23514", "ck_record_source_status");

            statement.executeUpdate("""
                    INSERT INTO public.attendance_record (
                        attendance_day_id,
                        policy_version_id,
                        member_id,
                        status,
                        source
                    )
                    VALUES (%d, %d, %d, 'ABSENT', 'AUTO_ABSENCE')
                    """.formatted(attendanceDay, policy, member));

            assertConstraintViolation(statement, """
                    INSERT INTO public.device (
                        department_id,
                        device_code,
                        name,
                        credential_hash
                    )
                    VALUES (
                        %d,
                        ' padded-device ',
                        '잘못된 장치 코드',
                        'test-only-hash'
                    )
                    """.formatted(
                            departmentA
                    ), "23514", "ck_device_code");

            assertConstraintViolation(statement, """
                    INSERT INTO public.device (
                        department_id,
                        device_code,
                        name,
                        credential_hash,
                        credential_tested_version
                    )
                    VALUES (
                        %d,
                        'version-without-time',
                        '잘못된 장치 1',
                        'test-only-hash',
                        1
                    )
                    """.formatted(
                            departmentA
                    ), "23514", "ck_device_credential_test");

            assertConstraintViolation(statement, """
                    INSERT INTO public.device (
                        department_id,
                        device_code,
                        name,
                        credential_hash,
                        credential_tested_at
                    )
                    VALUES (
                        %d,
                        'invalid-test-evidence',
                        '잘못된 장치',
                        'test-only-hash',
                        CURRENT_TIMESTAMP
                    )
                    """.formatted(
                            departmentA
                    ), "23514", "ck_device_credential_test");

            assertConstraintViolation(statement, """
                    INSERT INTO public.device (
                        department_id,
                        device_code,
                        name,
                        credential_hash,
                        credential_tested_version,
                        credential_tested_at
                    )
                    VALUES (
                        %d,
                        'mismatched-test-version',
                        '잘못된 장치 3',
                        'test-only-hash',
                        2,
                        CURRENT_TIMESTAMP
                    )
                    """.formatted(
                            departmentA
                    ), "23514", "ck_device_credential_test");

            assertConstraintViolation(statement, """
                    INSERT INTO public.device (
                        department_id,
                        device_code,
                        name,
                        credential_hash,
                        credential_issued_at,
                        credential_tested_version,
                        credential_tested_at
                    )
                    VALUES (
                        %d,
                        'test-before-issue',
                        '잘못된 장치 4',
                        'test-only-hash',
                        TIMESTAMPTZ '2026-08-02 00:10:00+00',
                        1,
                        TIMESTAMPTZ '2026-08-02 00:09:00+00'
                    )
                    """.formatted(
                            departmentA
                    ), "23514", "ck_device_credential_test");

            assertConstraintViolation(statement, """
                    INSERT INTO public.device (
                        department_id,
                        device_code,
                        name,
                        credential_hash,
                        status
                    )
                    VALUES (
                        %d,
                        'active-without-test',
                        '잘못된 장치 5',
                        'test-only-hash',
                        'ACTIVE'
                    )
                    """.formatted(
                            departmentA
                    ), "23514", "ck_device_active_credential_test");

            long device = queryLong(statement, """
                    INSERT INTO public.device (
                        department_id,
                        device_code,
                        name,
                        credential_hash
                    )
                    VALUES (
                        %d,
                        'valid-inactive-device',
                        '테스트 장치',
                        'test-only-hash'
                    )
                    RETURNING id
                    """.formatted(departmentA));

            assertConstraintViolation(statement, """
                    INSERT INTO public.tag_event_log (
                        device_id,
                        department_id,
                        request_id,
                        uid,
                        nfc_card_id,
                        result_code,
                        http_status,
                        response_body
                    )
                    VALUES (
                        %d,
                        %d,
                        'mismatched-card-uid',
                        'B1C2D3E4',
                        %d,
                        'UNKNOWN_UID',
                        404,
                        '{}'::jsonb
                    )
                    """.formatted(
                            device,
                            departmentA,
                            firstCard
                    ), "23503", "fk_tag_event_card");

            assertConstraintViolation(statement, """
                    INSERT INTO public.tag_event_log (
                        device_id,
                        department_id,
                        request_id,
                        uid,
                        result_code,
                        http_status,
                        response_body
                    )
                    VALUES (
                        %d,
                        %d,
                        'processing-with-response',
                        'A1B2C3D4',
                        'PROCESSING',
                        202,
                        '{}'::jsonb
                    )
                    """.formatted(
                            device,
                            departmentA
                    ), "23514", "ck_tag_event_response");

            assertConstraintViolation(statement, """
                    INSERT INTO public.tag_event_log (
                        device_id,
                        department_id,
                        request_id,
                        uid,
                        result_code,
                        response_body
                    )
                    VALUES (
                        %d,
                        %d,
                        'missing-http-status',
                        'A1B2C3D4',
                        'UNKNOWN_UID',
                        '{}'::jsonb
                    )
                    """.formatted(
                            device,
                            departmentA
                    ), "23514", "ck_tag_event_response");

            assertConstraintViolation(statement, """
                    INSERT INTO public.tag_event_log (
                        device_id,
                        department_id,
                        request_id,
                        uid,
                        result_code,
                        http_status
                    )
                    VALUES (
                        %d,
                        %d,
                        'missing-response-body',
                        'A1B2C3D4',
                        'UNKNOWN_UID',
                        404
                    )
                    """.formatted(
                            device,
                            departmentA
                    ), "23514", "ck_tag_event_response");
        }
    }

    @Test
    void adoptsExactLegacySchemaWithoutChangingRowsOrPrimaryKeys() throws Exception {
        Database database = createDatabase("legacy");

        try (Connection connection = database.connect()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/legacy/legacy-schema.sql")
            );
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO public.member (
                            id,
                            name,
                            age,
                            phone,
                            birth,
                            created_at,
                            card_uid
                        )
                        VALUES (
                            42,
                            '레거시 교사',
                            30,
                            '010-0000-0000',
                            DATE '1996-01-01',
                            TIMESTAMP '2025-01-01 09:00:00',
                            'A1B2C3D4'
                        )
                        """);
                statement.executeUpdate("""
                        INSERT INTO public.authentications (username, password, authority)
                        VALUES ('legacy-admin', 'legacy-hash', 'ADMIN')
                        """);
                statement.executeUpdate("""
                        INSERT INTO public.attendance (
                            member_id,
                            attend_time,
                            attend_date,
                            status,
                            note
                        )
                        VALUES (
                            42,
                            TIMESTAMP '2025-01-05 08:55:00',
                            DATE '2025-01-05',
                            'IN_TIME',
                            'legacy record'
                        )
                        """);
                statement.executeUpdate("""
                        INSERT INTO public.attendance_log (
                            member_id,
                            uid,
                            result,
                            message
                        )
                        VALUES (42, 'A1B2C3D4', 'SUCCESS', 'legacy event')
                        """);
            }
        }

        DatabasePreflightInspector inspector =
                new DatabasePreflightInspector();
        assertThat(inspector.inspect(database.dataSource()).status())
                .isEqualTo(LEGACY_CANDIDATE);

        assertThatThrownBy(() -> new DatabaseMigrationRunner().migrate(
                database.dataSource(),
                NEW_OR_SAMPLE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGACY_OPERATIONAL");
        try (Connection connection = database.connect()) {
            assertThat(queryString(connection, """
                    SELECT to_regclass('public.flyway_schema_history')::text
                    """)).isNull();
        }

        new DatabaseMigrationRunner().migrate(
                database.dataSource(),
                LEGACY_OPERATIONAL
        );

        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM public.flyway_schema_history
                    WHERE success
                    """)).isEqualTo(9);
            assertThat(queryInt(connection, "SELECT count(*) FROM public.member"))
                    .isEqualTo(1);
            assertThat(queryInt(connection, "SELECT count(*) FROM public.authentications"))
                    .isEqualTo(1);
            assertThat(queryInt(connection, "SELECT count(*) FROM public.attendance"))
                    .isEqualTo(1);
            assertThat(queryInt(connection, "SELECT count(*) FROM public.attendance_log"))
                    .isEqualTo(1);
            assertThat(queryLong(statement, """
                    SELECT id
                    FROM public.member
                    WHERE name = '레거시 교사'
                    """)).isEqualTo(42);
            assertThat(queryString(statement, """
                    SELECT active::text
                    FROM public.member
                    WHERE id = 42
                    """)).isEqualTo("false");
            assertThat(queryLong(statement, "SELECT nextval('public.member_id_seq')"))
                    .isGreaterThan(42);
            assertThat(queryString(statement, """
                    SELECT confdeltype::text
                    FROM pg_constraint
                    WHERE conname = 'fk_attendance_member'
                    """)).isEqualTo("r");
            assertThat(queryString(statement, """
                    SELECT confdeltype::text
                    FROM pg_constraint
                    WHERE conname = 'fk_attendance_log_member'
                    """)).isEqualTo("r");

            assertThatThrownBy(() ->
                    statement.executeUpdate("DELETE FROM public.member WHERE id = 42"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void acceptsAnExactLegacySchemaWhoseForeignKeysAreAlreadyRestricted()
            throws Exception {
        Database database = createDatabase("legacy_restrict");

        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/legacy/legacy-schema.sql")
            );
            statement.execute("""
                    ALTER TABLE public.attendance
                    DROP CONSTRAINT fk_attendance_member
                    """);
            statement.execute("""
                    ALTER TABLE public.attendance
                    ADD CONSTRAINT fk_attendance_member
                    FOREIGN KEY (member_id)
                    REFERENCES public.member (id)
                    ON DELETE RESTRICT
                    """);
            statement.execute("""
                    ALTER TABLE public.attendance_log
                    DROP CONSTRAINT fk_attendance_log_member
                    """);
            statement.execute("""
                    ALTER TABLE public.attendance_log
                    ADD CONSTRAINT fk_attendance_log_member
                    FOREIGN KEY (member_id)
                    REFERENCES public.member (id)
                    ON DELETE RESTRICT
                    """);
        }

        new DatabaseMigrationRunner().migrate(
                database.dataSource(),
                LEGACY_OPERATIONAL
        );

        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            assertThat(queryString(statement, """
                    SELECT confdeltype::text
                    FROM pg_constraint
                    WHERE conname = 'fk_attendance_member'
                    """)).isEqualTo("r");
            assertThat(queryString(statement, """
                    SELECT confdeltype::text
                    FROM pg_constraint
                    WHERE conname = 'fk_attendance_log_member'
                    """)).isEqualTo("r");
        }
    }

    @Test
    void rejectsUnknownMemberShapeWithoutCreatingFlywayHistory()
            throws Exception {
        Database database = createDatabase("unknown");

        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE public.member (
                        id BIGINT PRIMARY KEY,
                        name TEXT NOT NULL,
                        unexpected_column TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO public.member (id, name, unexpected_column)
                    VALUES (7, 'unknown shape', 'must survive')
                    """);
        }

        DatabasePreflightInspector.PreflightResult preflight =
                new DatabasePreflightInspector().inspect(
                        database.dataSource()
                );
        assertThat(preflight.status()).isEqualTo(REJECTED);

        assertThatThrownBy(() -> new DatabaseMigrationRunner().migrate(
                database.dataSource(),
                LEGACY_OPERATIONAL
        )).isInstanceOf(IllegalStateException.class);

        try (Connection connection = database.connect()) {
            assertThat(queryInt(connection, "SELECT count(*) FROM public.member"))
                    .isEqualTo(1);
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'member'
                      AND column_name = 'active'
                    """)).isZero();
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name = 'department'
                    """)).isZero();
            assertThat(queryString(connection, """
                    SELECT to_regclass('public.flyway_schema_history')::text
                    """)).isNull();
        }
    }

    @Test
    void rejectsFormerPublicSampleHashBeforeBaselineAndInsideV001()
            throws Exception {
        Database database = createDatabase("known_credentials");

        try (Connection connection = database.connect()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/legacy/legacy-schema.sql")
            );
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO public.authentications (
                            username,
                            password,
                            authority
                        )
                        VALUES (
                            'renamed-legacy-account',
                            '$2a$10$xrQiadbHq5UUR8GAy33.s.0/wD8skOmZNg7VUaOIxI6Y.ocFVZfl2',
                            'USER'
                        )
                        """);
            }
        }

        DatabasePreflightInspector.PreflightResult preflight =
                new DatabasePreflightInspector().inspect(
                        database.dataSource()
                );
        assertThat(preflight.status()).isEqualTo(REJECTED);

        assertThatThrownBy(() -> new DatabaseMigrationRunner().migrate(
                database.dataSource(),
                LEGACY_OPERATIONAL
        )).isInstanceOf(IllegalStateException.class);

        try (Connection connection = database.connect()) {
            assertThat(queryString(connection, """
                    SELECT to_regclass('public.flyway_schema_history')::text
                    """)).isNull();
            assertThat(queryInt(
                    connection,
                    "SELECT count(*) FROM public.authentications"
            )).isEqualTo(1);
        }

        Flyway bypassedRunner = flyway(database);
        bypassedRunner.baseline();
        assertThatThrownBy(bypassedRunner::migrate)
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining(
                        "former public sample credential hash"
                );

        try (Connection connection = database.connect()) {
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM public.flyway_schema_history
                    WHERE success
                    """)).isEqualTo(1);
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'member'
                      AND column_name = 'active'
                    """)).isZero();
            assertThat(queryString(connection, """
                    SELECT to_regclass('public.department')::text
                    """)).isNull();
            assertThat(queryInt(
                    connection,
                    "SELECT count(*) FROM public.authentications"
            )).isEqualTo(1);
        }
    }

    @Test
    void acceptsOnlyTheExactSuccessfulSchemaAtApplicationStartup()
            throws Exception {
        Database noHistory = createDatabase("no_history");
        assertThatThrownBy(() ->
                SchemaVersionGuard.verify(noHistory.dataSource()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");

        Database database = createDatabase("outdated");

        Flyway.configure()
                .dataSource(database.dataSource())
                .locations(MIGRATION_LOCATION)
                .defaultSchema("public")
                .schemas("public")
                .target(MigrationVersion.fromVersion("7"))
                .cleanDisabled(true)
                .load()
                .migrate();

        assertThatThrownBy(() ->
                SchemaVersionGuard.verify(database.dataSource()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");

        Database exact = createDatabase("exact_runtime");
        new DatabaseMigrationRunner().migrate(
                exact.dataSource(),
                NEW_OR_SAMPLE
        );
        SchemaVersionGuard.verify(exact.dataSource());

        try (Connection connection = exact.connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE public.flyway_schema_history
                    SET success = FALSE
                    WHERE version = '008'
                    """);
            assertThatThrownBy(() ->
                    SchemaVersionGuard.verify(exact.dataSource()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("incompatible");

            statement.executeUpdate("""
                    UPDATE public.flyway_schema_history
                    SET success = TRUE,
                        version = '009'
                    WHERE version = '008'
                    """);
            assertThatThrownBy(() ->
                    SchemaVersionGuard.verify(exact.dataSource()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("incompatible");
        }
    }

    private static Flyway flyway(Database database) {
        return Flyway.configure()
                .dataSource(database.url(), postgres.getUsername(), postgres.getPassword())
                .locations(MIGRATION_LOCATION)
                .defaultSchema("public")
                .schemas("public")
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .outOfOrder(false)
                .load();
    }

    private static Database createDatabase(String prefix) throws SQLException {
        String databaseName = "attend_" + prefix + "_"
                + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        String url = "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                databaseName
        );
        return new Database(url);
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static long queryLong(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String queryString(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static String queryString(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return queryString(statement, sql);
        }
    }

    private static Set<String> queryStrings(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            Set<String> values = new java.util.HashSet<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private static void assertConstraintViolation(
            Statement statement,
            String sql,
            String expectedSqlState,
            String expectedConstraint
    ) {
        assertThatThrownBy(() -> statement.executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(throwable -> {
                    SQLException exception = (SQLException) throwable;
                    assertThat(exception.getSQLState())
                            .isEqualTo(expectedSqlState);
                    assertThat(exception.getMessage())
                            .contains(expectedConstraint);
                });
    }

    private record Database(String url) {

        private DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource(
                    url,
                    postgres.getUsername(),
                    postgres.getPassword()
            );
        }

        private Connection connect() throws SQLException {
            return DriverManager.getConnection(
                    url,
                    postgres.getUsername(),
                    postgres.getPassword()
            );
        }
    }
}
