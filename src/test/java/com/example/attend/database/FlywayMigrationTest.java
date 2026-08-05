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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static com.example.attend.database.DatabaseMigrationRunner.ApprovedSourceClass.UNKNOWN;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.FRESH;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.LEGACY_CANDIDATE;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.REJECTED;

/**
 * 실제 PostgreSQL 15에서 V001~V009 migration의 안전성과 핵심 제약조건을 검증한다.
 *
 * <p>H2 같은 대체 DB로는 PostgreSQL catalog, partial unique index, 복합 외래 키,
 * SQLSTATE가 실제 운영 DB와 같다고 보장할 수 없다. 따라서 Testcontainers로
 * PostgreSQL을 실행하고 테스트마다 독립 데이터베이스를 만들어 서로의 스키마와
 * 데이터를 공유하지 않게 한다.</p>
 */
@Testcontainers
class FlywayMigrationTest {

    /**
     * 운영 실행기와 테스트용 Flyway가 함께 사용하는 migration 경로다.
     */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /**
     * 이 테스트 클래스의 모든 테스트가 공유하는 PostgreSQL 서버 컨테이너다.
     *
     * <p>서버 프로세스만 공유하고 각 테스트의 데이터베이스는
     * {@link #createDatabase(String)}가 별도로 생성한다.</p>
     */
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine");

    /**
     * 빈 DB가 올바르게 분류되고 V009까지 정확히 한 번 적용되는지 검증한다.
     *
     * <p>잘못된 운영자 승인값에서는 history조차 만들지 않아야 하며, 같은
     * migration을 다시 실행해도 결과가 바뀌지 않는 멱등성도 함께 확인한다.</p>
     */
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
                UNKNOWN
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNKNOWN");

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
                      AND version IS NOT NULL
                    """)).isEqualTo(9);

            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM public.flyway_schema_history
                    WHERE success
                      AND version IS NULL
                      AND script = 'R__update_member_column_comments.sql'
                    """)).isEqualTo(1);
            assertThat(queryString(connection, """
                    SELECT col_description(
                        'public.member'::regclass,
                        (SELECT attnum
                         FROM pg_attribute
                         WHERE attrelid = 'public.member'::regclass
                           AND attname = 'birth'))
                    """)).contains("birthday management");

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
                        'trg_attendance_record_updated_at',
                        'trg_member_birth_required_on_insert',
                        'trg_member_birth_required_on_operational_update',
                        'trg_membership_member_required_on_insert',
                        'trg_membership_write_guard_on_update',
                        'trg_card_assignment_closed_history_immutable'
                      )
                    """)).isEqualTo(11);
            assertThat(queryString(connection, """
                    SELECT (NOT EXISTS (
                        SELECT 1
                        FROM pg_proc AS routine
                        CROSS JOIN LATERAL aclexplode(
                            COALESCE(
                                routine.proacl,
                                acldefault('f', routine.proowner)
                            )
                        ) AS privilege
                        WHERE routine.oid IN (
                              'public.attend_require_member_birth_on_write()'::regprocedure,
                              'public.attend_require_operational_membership_member()'::regprocedure,
                              'public.attend_require_closed_card_assignment_immutable()'::regprocedure
                        )
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE'
                    ))::text
                    """)).isEqualTo("true");
        }
    }

    /**
     * V009는 기존 생년월일 NULL을 조작하지 않으면서 이후 업무 write를 제한한다.
     *
     * <p>V008 상태에서 존재하던 NULL 행과 소속은 그대로 채택한다. 소속이 없는
     * 레거시 행은 생년월일을 만들지 않고 비활성화할 수 있지만 신규 등록·기본정보
     * 변경·활성화에는 미래가 아닌 정확한 날짜가 필요하다. 새 활성 소속도 같은
     * 조건의 교사만 가리키며, 소속을 먼저 끝내지 않은 비활성화는 거부한다.</p>
     */
    @Test
    void requiresVerifiedBirthAndConsistentMembershipWithoutInventingLegacyDates()
            throws Exception {
        Database database = createDatabase("verified_birth");
        Flyway.configure()
                .dataSource(database.url(), postgres.getUsername(), postgres.getPassword())
                .locations(MIGRATION_LOCATION)
                .defaultSchema("public")
                .schemas("public")
                .target(MigrationVersion.fromVersion("8"))
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .outOfOrder(false)
                .load()
                .migrate();

        long legacyActiveMemberId;
        long legacyInactiveMemberId;
        long legacyMembershipMemberId;
        long legacyFutureMemberId;
        long departmentId;
        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            legacyActiveMemberId = queryLong(statement, """
                    INSERT INTO public.member(name, active)
                    VALUES ('생년월일 미확인 활성 교사', TRUE)
                    RETURNING id
                    """);
            legacyInactiveMemberId = queryLong(statement, """
                    INSERT INTO public.member(name)
                    VALUES ('생년월일 미확인 비활성 교사')
                    RETURNING id
                    """);
            legacyMembershipMemberId = queryLong(statement, """
                    INSERT INTO public.member(name, active)
                    VALUES ('기존 소속 생년월일 미확인 교사', TRUE)
                    RETURNING id
                    """);
            legacyFutureMemberId = queryLong(statement, """
                    INSERT INTO public.member(name, birth, active)
                    VALUES ('기존 미래 생년월일 교사', DATE '2999-01-01', TRUE)
                    RETURNING id
                    """);
            departmentId = queryLong(statement, """
                    INSERT INTO public.department(name)
                    VALUES ('V009 무결성 부서')
                    RETURNING id
                    """);
            statement.executeUpdate("""
                    INSERT INTO public.department_membership(
                        department_id,
                        member_id
                    )
                    VALUES (%d, %d)
                    """.formatted(departmentId, legacyMembershipMemberId));
        }

        flyway(database).migrate();

        try (Connection connection = database.connect();
             Statement statement = connection.createStatement()) {
            assertThat(queryString(statement, """
                    SELECT birth::text
                    FROM public.member
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId))).isNull();
            assertThat(queryString(statement, """
                    SELECT active::text
                    FROM public.member
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId))).isEqualTo("true");
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM public.department_membership
                    WHERE department_id = %d
                      AND member_id = %d
                      AND ended_at IS NULL
                    """.formatted(departmentId, legacyMembershipMemberId)))
                    .isEqualTo(1);
            long administratorId = queryLong(statement, """
                    INSERT INTO public.account(username)
                    VALUES ('v009-history-admin')
                    RETURNING id
                    """);

            assertMemberBirthViolation(statement, """
                    INSERT INTO public.member(name, active)
                    VALUES ('신규 활성 NULL 교사', TRUE)
                    """);
            assertMemberBirthViolation(statement, """
                    INSERT INTO public.member(name)
                    VALUES ('신규 비활성 NULL 교사')
                    """);
            assertMemberBirthViolation(statement, """
                    UPDATE public.member
                    SET name = '생년월일 없는 기본정보 수정'
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId));

            statement.executeUpdate("""
                    UPDATE public.member
                    SET active = FALSE
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId));
            assertThat(queryString(statement, """
                    SELECT birth::text
                    FROM public.member
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId))).isNull();

            assertMemberBirthViolation(statement, """
                    UPDATE public.member
                    SET active = TRUE
                    WHERE id = %d
                    """.formatted(legacyInactiveMemberId));
            assertMemberBirthViolation(statement, """
                    UPDATE public.member
                    SET phone = '010-9999-9999'
                    WHERE id = %d
                    """.formatted(legacyInactiveMemberId));

            statement.executeUpdate("""
                    UPDATE public.member
                    SET name = '생년월일 확인 교사',
                        birth = DATE '1990-01-02',
                        active = TRUE
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId));
            assertThat(queryString(statement, """
                    SELECT birth::text
                    FROM public.member
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId)))
                    .isEqualTo("1990-01-02");
            long verifiedMembershipId = queryLong(statement, """
                    INSERT INTO public.department_membership(
                        department_id,
                        member_id,
                        created_by_account_id
                    )
                    VALUES (%d, %d, %d)
                    RETURNING id
                    """.formatted(
                            departmentId,
                            legacyActiveMemberId,
                            administratorId
                    ));
            assertMemberStateViolation(statement, """
                    UPDATE public.member
                    SET active = FALSE
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId));
            assertMemberBirthViolation(statement, """
                    UPDATE public.member
                    SET birth = NULL
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId));
            assertMemberFutureBirthViolation(statement, """
                    INSERT INTO public.member(name, birth)
                    VALUES ('신규 미래 생년월일 교사', DATE '2999-01-01')
                    """);
            assertMemberFutureBirthViolation(statement, """
                    UPDATE public.member
                    SET birth = DATE '2999-01-01'
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId));
            assertOperationalMembershipViolation(statement, """
                    INSERT INTO public.department_membership(
                        department_id,
                        member_id
                    )
                    VALUES (%d, %d)
                    """.formatted(departmentId, legacyInactiveMemberId));
            assertOperationalMembershipViolation(statement, """
                    INSERT INTO public.department_membership(
                        department_id,
                        member_id
                    )
                    VALUES (%d, %d)
                    """.formatted(departmentId, legacyFutureMemberId));
            assertMemberStateViolation(statement, """
                    UPDATE public.member
                    SET active = FALSE
                    WHERE id = %d
                    """.formatted(legacyMembershipMemberId));

            long cardId = queryLong(statement, """
                    INSERT INTO public.nfc_card(uid, status)
                    VALUES ('AABBCCDD', 'ACTIVE')
                    RETURNING id
                    """);
            long assignmentId = queryLong(statement, """
                    INSERT INTO public.nfc_card_assignment(
                        nfc_card_id,
                        department_id,
                        membership_id,
                        member_id,
                        assigned_by_account_id
                    )
                    VALUES (%d, %d, %d, %d, %d)
                    RETURNING id
                    """.formatted(
                            cardId,
                            departmentId,
                            verifiedMembershipId,
                            legacyActiveMemberId,
                            administratorId
                    ));
            statement.executeUpdate("""
                    UPDATE public.nfc_card_assignment
                    SET unassigned_by_account_id = %d,
                        unassigned_at = CURRENT_TIMESTAMP,
                        end_reason = '카드 종료'
                    WHERE id = %d
                    """.formatted(administratorId, assignmentId));
            assertClosedCardAssignmentViolation(statement, """
                    UPDATE public.nfc_card_assignment
                    SET unassigned_by_account_id = NULL,
                        unassigned_at = NULL,
                        end_reason = NULL
                    WHERE id = %d
                    """.formatted(assignmentId));
            assertClosedCardAssignmentViolation(statement, """
                    UPDATE public.nfc_card_assignment
                    SET end_reason = '종료 사유 변조'
                    WHERE id = %d
                    """.formatted(assignmentId));

            statement.executeUpdate("""
                    UPDATE public.department_membership
                    SET ended_by_account_id = %d,
                        ended_at = CURRENT_TIMESTAMP,
                        end_reason = '소속 종료'
                    WHERE id = %d
                    """.formatted(administratorId, verifiedMembershipId));
            assertClosedMembershipViolation(statement, """
                    UPDATE public.department_membership
                    SET ended_by_account_id = NULL,
                        ended_at = NULL,
                        end_reason = NULL
                    WHERE id = %d
                    """.formatted(verifiedMembershipId));
            assertClosedMembershipViolation(statement, """
                    UPDATE public.department_membership
                    SET end_reason = '종료 사유 변조'
                    WHERE id = %d
                    """.formatted(verifiedMembershipId));
            statement.executeUpdate("""
                    UPDATE public.member
                    SET active = FALSE
                    WHERE id = %d
                    """.formatted(legacyActiveMemberId));
        }
    }

    /**
     * 관리자 계정의 초기 상태와 회원가입 초대 토큰의 일회성 규칙을 검증한다.
     *
     * <p>서비스 코드가 실수하더라도 DB가 계정 상태 조합, 토큰 hash 형식,
     * 30분 수명, 발급자 참조, 소비·폐기 시각, 활성 토큰 중복을 거부해야 한다.</p>
     */
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

    /**
     * 부서 경계를 넘는 참조와 “현재 활성 행은 하나” 규칙을 검증한다.
     *
     * <p>복합 외래 키가 소속·카드·장치·출석정책의 부서 범위를 지키는지,
     * partial unique index가 과거 이력은 보존하면서 현재 배정 중복만 막는지,
     * 상태별 CHECK 제약이 잘못된 조합을 차단하는지 실제 SQL로 확인한다.</p>
     */
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
                    INSERT INTO public.member (name, birth, active)
                    VALUES ('소속 교사', DATE '1990-05-06', TRUE)
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

    /**
     * 정확한 레거시 DB를 채택해도 기존 행과 기본 키가 보존되는지 검증한다.
     *
     * <p>사전검사 승인 전에는 baseline history를 만들지 않으며, 승인 후에는
     * 기존 교사 ID를 유지하고 sequence만 다음 안전한 값으로 이동한다. 또한
     * 과거 출석 이력을 교사 삭제가 연쇄 삭제하지 못하도록 FK를 제한한다.</p>
     */
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
                      AND version IS NOT NULL
                    """)).isEqualTo(10);
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM public.flyway_schema_history
                    WHERE success
                      AND version IS NULL
                      AND script = 'R__update_member_column_comments.sql'
                    """)).isEqualTo(1);
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

    /**
     * 레거시 외래 키가 이미 {@code ON DELETE RESTRICT}인 경우도 안전하게 채택한다.
     *
     * <p>V001은 허용된 두 출발 형태를 구분하되 최종 결과는 항상 같은 삭제
     * 제한으로 수렴해야 한다.</p>
     */
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

    /**
     * 이름만 같은 알 수 없는 {@code member} 테이블을 레거시로 추측하지 않는지 검증한다.
     *
     * <p>거부된 DB에서는 기존 행을 수정하지 않고, 새 업무 테이블과 Flyway
     * history도 만들지 않는 fail-closed 동작이 핵심이다.</p>
     */
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

    /**
     * 과거에 공개됐던 샘플 자격증명이 남은 DB를 두 방어선에서 거부한다.
     *
     * <p>정상 실행 경로의 사전검사가 baseline 전에 차단하고, 누군가 실행기를
     * 우회해 Flyway를 직접 호출해도 V001 자체가 첫 구조 변경 전에 다시
     * 차단하는지 검증한다.</p>
     */
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

    /**
     * 애플리케이션 시작 검사가 정확히 성공한 V001~V009만 허용하는지 검증한다.
     *
     * <p>history 없음, 구버전, 실패 처리된 migration, 애플리케이션보다 앞선
     * 버전을 모두 거부하고 정확한 버전 목록만 통과시킨다.</p>
     */
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
                .target(MigrationVersion.fromVersion("8"))
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
                    WHERE version = '009'
                    """);
            assertThatThrownBy(() ->
                    SchemaVersionGuard.verify(exact.dataSource()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("incompatible");

            statement.executeUpdate("""
                    UPDATE public.flyway_schema_history
                    SET success = TRUE,
                        version = '010'
                    WHERE version = '009'
                    """);
            assertThatThrownBy(() ->
                    SchemaVersionGuard.verify(exact.dataSource()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("incompatible");
        }
    }

    /**
     * migration 계정과 웹 runtime 계정의 실제 PostgreSQL 권한이 분리되는지 검증한다.
     *
     * <p>한 테스트 안에서 역할 생성, 레거시 migration, V009 이후 grant와 runtime
     * guard를 모두 실행한다. runtime의 교사 등록·조회·수정은 실제 사용 컬럼까지
     * 허용하면서 DDL, Flyway history 변경, 교사 삭제·card_uid 접근과 레거시
     * 출석 쓰기는 권한 오류로 막아야 한다.</p>
     */
    @Test
    void separatesMigrationOwnerFromRuntimeDatabasePrivileges()
            throws Exception {
        Database database = createDatabase("roles");
        String migrationPassword = "test-only-migration-password";
        String runtimePassword = "test-only-runtime-password";

        try (Connection admin = database.connect();
             Statement statement = admin.createStatement()) {
            executeSqlFile(
                    statement,
                    "ops/db/roles/001_create_login_roles.sql"
            );
            statement.execute("""
                    ALTER ROLE migration_owner PASSWORD '%s'
                    """.formatted(migrationPassword));
            statement.execute("""
                    ALTER ROLE app_runtime PASSWORD '%s'
                    """.formatted(runtimePassword));
            executeSqlFile(
                    statement,
                    "ops/db/roles/002_prepare_database_for_migration.sql"
            );
        }

        DriverManagerDataSource migrationDataSource =
                database.dataSource(
                        "migration_owner",
                        migrationPassword
                );
        try (Connection migrationConnection =
                     migrationDataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    migrationConnection,
                    new ClassPathResource("db/legacy/legacy-schema.sql")
            );
        }

        new DatabaseMigrationRunner().migrate(
                migrationDataSource,
                LEGACY_OPERATIONAL
        );

        try (Connection migrationConnection =
                     migrationDataSource.getConnection();
             Statement statement =
                     migrationConnection.createStatement()) {
            executeSqlFile(
                    statement,
                    "ops/db/roles/003_grant_application_privileges.sql"
            );
        }

        DriverManagerDataSource runtimeDataSource =
                database.dataSource("app_runtime", runtimePassword);
        SchemaVersionGuard.verify(runtimeDataSource);
        RuntimeDatabasePrivilegeGuard.verify(runtimeDataSource);

        try (Connection runtimeConnection =
                     runtimeDataSource.getConnection();
             Statement statement = runtimeConnection.createStatement()) {
            long memberId = queryLong(statement, """
                    INSERT INTO public.member (name, phone, birth, active)
                    VALUES (
                        '권한 테스트 교사',
                        '010-0000-0000',
                        DATE '1990-01-02',
                        TRUE
                    )
                    RETURNING id
                    """);
            long departmentId = queryLong(statement, """
                    INSERT INTO public.department(name)
                    VALUES ('runtime 권한 테스트 부서')
                    RETURNING id
                    """);
            statement.executeUpdate("""
                    INSERT INTO public.department_membership(
                        department_id,
                        member_id
                    )
                    VALUES (%d, %d)
                    """.formatted(departmentId, memberId));
            statement.executeUpdate("""
                    UPDATE public.member
                    SET phone = '010-1111-1111',
                        birth = DATE '1991-02-03'
                    WHERE id = %d
                    """.formatted(memberId));

            assertThat(queryString(statement, """
                    SELECT birth::text
                    FROM public.member
                    WHERE id = %d
                    """.formatted(memberId)))
                    .isEqualTo("1991-02-03");
            assertThat(queryString(statement, """
                    SELECT created_at::text
                    FROM public.member
                    WHERE id = %d
                    """.formatted(memberId)))
                    .isNotBlank();
            assertThat(queryString(statement, """
                    SELECT has_function_privilege(
                        current_user,
                        'public.attend_require_member_birth_on_write()',
                        'EXECUTE'
                    )::text
                    """)).isEqualTo("false");
            assertThat(queryString(statement, """
                    SELECT has_function_privilege(
                        current_user,
                        'public.attend_require_operational_membership_member()',
                        'EXECUTE'
                    )::text
                    """)).isEqualTo("false");
            assertThat(queryString(statement, """
                    SELECT has_function_privilege(
                        current_user,
                        'public.attend_require_closed_card_assignment_immutable()',
                        'EXECUTE'
                    )::text
                    """)).isEqualTo("false");

            assertPermissionDenied(
                    statement,
                    "CREATE TABLE public.runtime_must_not_create (id BIGINT)"
            );
            assertPermissionDenied(
                    statement,
                    "CREATE TEMP TABLE runtime_temp_must_not_create (id BIGINT)"
            );
            assertPermissionDenied(statement, """
                    UPDATE public.flyway_schema_history
                    SET success = FALSE
                    WHERE version = '009'
                    """);
            assertPermissionDenied(
                    statement,
                    "DELETE FROM public.member WHERE id = " + memberId
            );
            assertPermissionDenied(statement, """
                    SELECT age
                    FROM public.member
                    WHERE id = %d
                    """.formatted(memberId));
            assertPermissionDenied(statement, """
                    SELECT card_uid
                    FROM public.member
                    WHERE id = %d
                    """.formatted(memberId));
            assertPermissionDenied(statement, """
                    UPDATE public.member
                    SET card_uid = 'blocked-card'
                    WHERE id = %d
                    """.formatted(memberId));
            assertPermissionDenied(statement, """
                    INSERT INTO public.attendance (
                        member_id,
                        attend_date,
                        status
                    )
                    VALUES (%d, CURRENT_DATE, 'IN_TIME')
                    """.formatted(memberId));
        }

        try (Connection migrationConnection =
                     migrationDataSource.getConnection();
             Statement statement = migrationConnection.createStatement()) {
            statement.execute("""
                    GRANT SELECT (age)
                    ON TABLE public.member
                    TO app_runtime
                    """);
        }
        assertRuntimePrivilegeGuardRejects(runtimeDataSource);
        try (Connection migrationConnection =
                     migrationDataSource.getConnection();
             Statement statement = migrationConnection.createStatement()) {
            statement.execute("""
                    REVOKE SELECT (age)
                    ON TABLE public.member
                    FROM app_runtime
                    """);
            statement.execute("""
                    GRANT SELECT
                    ON TABLE public.member
                    TO app_runtime
                    """);
        }
        assertRuntimePrivilegeGuardRejects(runtimeDataSource);
        try (Connection migrationConnection =
                     migrationDataSource.getConnection();
             Statement statement = migrationConnection.createStatement()) {
            executeSqlFile(
                    statement,
                    "ops/db/roles/003_grant_application_privileges.sql"
            );
        }
        RuntimeDatabasePrivilegeGuard.verify(runtimeDataSource);

        assertThatThrownBy(() ->
                RuntimeDatabasePrivilegeGuard.verify(
                        migrationDataSource
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
    }

    /** 운영 member 권한이 허용 경계보다 넓으면 기동 guard가 실패해야 한다. */
    private static void assertRuntimePrivilegeGuardRejects(
            DataSource dataSource
    ) {
        assertThatThrownBy(() ->
                RuntimeDatabasePrivilegeGuard.verify(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
    }

    /**
     * 제약조건 자체만 시험할 때 사용할 표준 Flyway 설정을 만든다.
     *
     * @param database 테스트가 소유한 독립 데이터베이스
     * @return 운영 설정과 같은 안전 옵션을 적용한 Flyway 인스턴스
     */
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

    /**
     * 공유 PostgreSQL 서버 안에 테스트 전용 데이터베이스를 만든다.
     *
     * <p>prefix 뒤에 UUID를 붙여 병렬 실행과 재실행에서도 이름 충돌을 피한다.</p>
     *
     * @param prefix 실패 로그에서 테스트 목적을 식별할 짧은 이름
     * @return 새 데이터베이스의 JDBC URL을 가진 값 객체
     * @throws SQLException 데이터베이스 생성에 실패할 때
     */
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

    /**
     * 단일 정수 값을 반환하는 SQL을 실행한다.
     *
     * @param connection 사용할 JDBC 연결
     * @param sql 한 행·한 열을 반환하는 조회문
     * @return 첫 행의 첫 번째 정수 값
     * @throws SQLException 조회가 실패할 때
     */
    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * 기존 statement로 INSERT ... RETURNING 같은 단일 long 값을 읽는다.
     *
     * @param statement 현재 테스트 흐름에서 재사용할 statement
     * @param sql 한 행·한 열을 반환하는 SQL
     * @return 첫 행의 첫 번째 long 값
     * @throws SQLException 조회가 실패할 때
     */
    private static long queryLong(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * 기존 statement로 단일 문자열 값을 조회한다.
     *
     * @param statement 현재 테스트 흐름에서 재사용할 statement
     * @param sql 한 행·한 열을 반환하는 SQL
     * @return 첫 행의 첫 번째 문자열이며 SQL {@code NULL}이면 {@code null}
     * @throws SQLException 조회가 실패할 때
     */
    private static String queryString(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    /**
     * 연결에서 새 statement를 열어 단일 문자열 값을 조회한다.
     *
     * @param connection 사용할 JDBC 연결
     * @param sql 한 행·한 열을 반환하는 SQL
     * @return 첫 행의 첫 번째 문자열이며 SQL {@code NULL}이면 {@code null}
     * @throws SQLException 조회가 실패할 때
     */
    private static String queryString(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return queryString(statement, sql);
        }
    }

    /**
     * 한 열의 여러 행을 집합으로 읽어 순서와 무관하게 비교할 수 있게 한다.
     *
     * @param connection 사용할 JDBC 연결
     * @param sql 여러 행의 문자열 한 열을 반환하는 SQL
     * @return 중복을 제거한 문자열 집합
     * @throws SQLException 조회가 실패할 때
     */
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

    /**
     * SQL이 예상한 PostgreSQL 제약조건 때문에 실패했는지 확인한다.
     *
     * <p>단순히 “예외가 발생했다”만 검사하면 문법 오류나 연결 장애도 테스트를
     * 통과시킬 수 있다. SQLSTATE와 제약조건 이름을 함께 비교해 의도한 DB 규칙이
     * 실제 실패 원인인지 증명한다.</p>
     *
     * @param statement SQL을 실행할 statement
     * @param sql 실패해야 하는 SQL
     * @param expectedSqlState 예상 PostgreSQL SQLSTATE
     * @param expectedConstraint 실패를 일으켜야 하는 제약조건 이름
     */
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

    /** 필수 생년월일 write trigger가 의도한 오류로 거부했는지 확인한다. */
    private static void assertMemberBirthViolation(
            Statement statement,
            String sql
    ) {
        assertWriteViolation(
                statement,
                sql,
                "member birth date is required for this write"
        );
    }

    /** 미래 생년월일 write가 고정 오류로 거부됐는지 확인한다. */
    private static void assertMemberFutureBirthViolation(
            Statement statement,
            String sql
    ) {
        assertWriteViolation(
                statement,
                sql,
                "member birth date cannot be in the future"
        );
    }

    /** 활성 소속이 남은 교사의 비활성화를 거부했는지 확인한다. */
    private static void assertMemberStateViolation(
            Statement statement,
            String sql
    ) {
        assertWriteViolation(
                statement,
                sql,
                "member with an active membership cannot be deactivated"
        );
    }

    /** 운영 조건을 충족하지 않는 교사의 활성 소속 생성을 거부했는지 확인한다. */
    private static void assertOperationalMembershipViolation(
            Statement statement,
            String sql
    ) {
        assertWriteViolation(
                statement,
                sql,
                "active membership requires an active member with a verified birth date"
        );
    }

    /** 종료된 소속 행의 재개방·변조를 거부했는지 확인한다. */
    private static void assertClosedMembershipViolation(
            Statement statement,
            String sql
    ) {
        assertWriteViolation(
                statement,
                sql,
                "closed membership history is immutable"
        );
    }

    /** 종료된 카드 연결 행의 재개방·변조를 거부했는지 확인한다. */
    private static void assertClosedCardAssignmentViolation(
            Statement statement,
            String sql
    ) {
        assertWriteViolation(
                statement,
                sql,
                "closed card assignment history is immutable"
        );
    }

    /** V009 업무 write trigger의 공통 SQLSTATE와 메시지를 확인한다. */
    private static void assertWriteViolation(
            Statement statement,
            String sql,
            String message
    ) {
        assertThatThrownBy(() -> statement.executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(throwable -> {
                    SQLException exception = (SQLException) throwable;
                    assertThat(exception.getSQLState()).isEqualTo("23514");
                    assertThat(exception.getMessage()).contains(message);
                });
    }

    /**
     * 프로젝트의 운영 SQL 파일 전체를 JDBC statement 하나로 실행한다.
     *
     * @param statement SQL을 실행할 statement
     * @param relativePath 저장소 루트 기준 SQL 파일 경로
     * @throws Exception 파일을 읽거나 SQL을 실행하지 못할 때
     */
    private static void executeSqlFile(
            Statement statement,
            String relativePath
    ) throws Exception {
        String sql = Files.readString(
                Path.of(relativePath),
                StandardCharsets.UTF_8
        );
        statement.execute(sql);
    }

    /**
     * SQL이 PostgreSQL 권한 부족으로 거부되는지 확인한다.
     *
     * @param statement 제한된 runtime 계정의 statement
     * @param sql 거부되어야 하는 SQL
     */
    private static void assertPermissionDenied(
            Statement statement,
            String sql
    ) {
        assertThatThrownBy(() -> statement.execute(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(throwable -> assertThat(
                        ((SQLException) throwable).getSQLState()
                ).isEqualTo("42501"));
    }

    /**
     * 테스트 데이터베이스 접속정보를 한 값으로 묶는다.
     *
     * @param url 테스트 데이터베이스 JDBC URL
     */
    private record Database(String url) {

        /**
         * 사전검사기와 migration 실행기에 전달할 DataSource를 만든다.
         *
         * @return 테스트 컨테이너 계정을 사용하는 DataSource
         */
        private DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource(
                    url,
                    postgres.getUsername(),
                    postgres.getPassword()
            );
        }

        /**
         * 지정한 DB 역할로 연결되는 DataSource를 만든다.
         *
         * @param username 테스트할 PostgreSQL 역할 이름
         * @param password 해당 역할의 테스트 전용 비밀번호
         * @return 지정한 역할 자격증명을 사용하는 DataSource
         */
        private DriverManagerDataSource dataSource(
                String username,
                String password
        ) {
            return new DriverManagerDataSource(url, username, password);
        }

        /**
         * 직접 SQL 검증에 사용할 JDBC 연결을 연다.
         *
         * @return 호출자가 닫아야 하는 새 연결
         * @throws SQLException 연결에 실패할 때
         */
        private Connection connect() throws SQLException {
            return DriverManager.getConnection(
                    url,
                    postgres.getUsername(),
                    postgres.getPassword()
            );
        }
    }
}
