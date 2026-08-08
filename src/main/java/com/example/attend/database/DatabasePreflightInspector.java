package com.example.attend.database;

import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Set;

/**
 * Flyway가 DB를 변경하기 전에 현재 구조가 허용된 입력인지 읽기 전용으로 판정한다.
 *
 * <p>허용하는 입력은 완전히 빈 {@code public} 스키마, 저장소가 알고 있는
 * 정확한 레거시 스키마, 이미 Flyway가 관리하는 스키마뿐이다. 일부 테이블만
 * 존재하거나 컬럼·제약조건이 다른 DB는 자동으로 고치지 않고 거부한다.</p>
 *
 * <p>레거시 구조 검증 규칙을 Java와 SQL에 따로 복사하면 두 규칙이 어긋날 수
 * 있다. 이를 피하기 위해 V001의 검증 블록을 읽기 전용 트랜잭션에서 실행한다.
 * 검증을 모두 통과해 첫 DDL 경계에 도달하면 PostgreSQL SQLSTATE {@code 25006}
 * (read-only SQL transaction)를 반환한다. 이 오류만 “구조 검증 성공”으로
 * 해석하고 마지막에는 항상 rollback한다.</p>
 */
public final class DatabasePreflightInspector {

    private static final String READ_ONLY_SQL_STATE = "25006";
    private static final String V001_RESOURCE =
            "db/migration/V001__adopt_or_create_member.sql";
    private static final String V001_END_MARKER = "\n$v001$;";
    private static final String EXCLUSIVE_LOCK = "IN ACCESS EXCLUSIVE MODE';";
    private static final String READ_ONLY_LOCK = "IN ACCESS SHARE MODE';";

    /*
     * SHA-256 fingerprints of the former public sample password hashes.
     *
     * Neither the reusable password nor its BCrypt hash is embedded in the
     * production artifact.
     */
    private static final Set<String> REJECTED_LEGACY_CREDENTIAL_FINGERPRINTS =
            Set.of(
                    "2ea32b4e8d0f2b170a58b152778ddad5630e9b3b2de747beeab7d0fccfd3fbfa",
                    "6b819d3d621468b057b6254132cf3f189df68a0b49060ddc6ec529d47c403ccf"
            );

    private final String readOnlyV001ValidationSql;

    /**
     * classpath의 V001에서 읽기 전용 사전검사용 SQL을 준비한다.
     *
     * @throws IllegalStateException V001 리소스나 예상한 검증 경계를 찾을 수 없을 때
     */
    public DatabasePreflightInspector() {
        this.readOnlyV001ValidationSql = loadReadOnlyV001ValidationSql();
    }

    /**
     * DB를 변경하지 않고 Flyway 실행 가능 여부를 판정한다.
     *
     * @param dataSource 검사할 PostgreSQL 데이터소스
     * @return 판정 상태와 운영자가 확인할 수 있는 이유
     * @throws IllegalStateException 연결·catalog 조회 등 사전검사 자체를 완료하지 못할 때
     */
    public PreflightResult inspect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 이 트랜잭션에서 실수로 DDL/DML이 실행되어도 PostgreSQL이 거부한다.
                execute(connection, "SET TRANSACTION READ ONLY");

                // 모든 migration은 명시적으로 public 스키마를 기준으로 작성돼 있다.
                if (!"public".equals(queryString(
                        connection,
                        "SELECT current_schema()"
                ))) {
                    return PreflightResult.rejected(
                            "current schema is not public"
                    );
                }

                boolean flywayHistoryExists = queryBoolean(connection, """
                        SELECT to_regclass('public.flyway_schema_history')
                               IS NOT NULL
                        """);

                int legacyTableCount = queryInt(connection, """
                        SELECT count(*)
                        FROM pg_catalog.pg_class AS relation
                        JOIN pg_catalog.pg_namespace AS namespace
                          ON namespace.oid = relation.relnamespace
                        WHERE namespace.nspname = 'public'
                          AND relation.relname = ANY (
                            ARRAY[
                              'member',
                              'authentications',
                              'attendance',
                              'attendance_log'
                            ]
                          )
                          AND relation.relkind IN ('r', 'p')
                        """);

                // 알려진 공개 샘플 hash는 사용자명이나 권한을 바꿔도 거부한다.
                if (legacyTableCount == 4) {
                    try {
                        if (containsRejectedLegacyCredential(connection)) {
                            return PreflightResult.rejected(
                                    "known public sample credentials are present"
                            );
                        }
                    } catch (SQLException exception) {
                        return PreflightResult.rejected(
                                "legacy authentication schema is not accepted"
                        );
                    }
                }

                String conflictingFunctionVersion =
                        conflictingReservedFunctionVersion(
                                connection,
                                flywayHistoryExists
                        );
                if (conflictingFunctionVersion != null) {
                    return PreflightResult.rejected(
                            "unapplied " + conflictingFunctionVersion
                                    + " function conflicts with migration"
                    );
                }

                // 기존 history가 있으면 baseline 후보가 아니며 Flyway validate 대상으로 넘긴다.
                if (flywayHistoryExists) {
                    return new PreflightResult(
                            PreflightStatus.ALREADY_MANAGED,
                            "Flyway history already exists"
                    );
                }

                /*
                 * V001 검증이 성공하면 fresh 경로에서는 CREATE TABLE, legacy
                 * 경로에서는 ALTER TABLE에 도달한다. 읽기 전용 오류가 아닌 다른
                 * SQL 오류는 구조 불일치로 본다.
                 */
                try {
                    execute(connection, readOnlyV001ValidationSql);
                    return PreflightResult.rejected(
                            "V001 validation did not reach its expected write boundary"
                    );
                } catch (SQLException exception) {
                    if (!READ_ONLY_SQL_STATE.equals(exception.getSQLState())) {
                        return PreflightResult.rejected(
                                "schema does not match an accepted V001 input"
                        );
                    }
                }

                if (legacyTableCount == 0) {
                    return new PreflightResult(
                            PreflightStatus.FRESH,
                            "empty public schema accepted"
                    );
                }
                if (legacyTableCount == 4) {
                    return new PreflightResult(
                            PreflightStatus.LEGACY_CANDIDATE,
                            "exact legacy schema accepted"
                    );
                }
                return PreflightResult.rejected(
                        "partial legacy table inventory"
                );
            } finally {
                // 정상 반환과 예외 경로 모두에서 사전검사 트랜잭션을 폐기한다.
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Database preflight could not be completed (SQLSTATE "
                            + exception.getSQLState() + ")");
        }
    }

    /**
     * 레거시 인증 테이블에 삭제된 공개 샘플 비밀번호 hash가 남았는지 확인한다.
     *
     * @param connection 읽기 전용 사전검사 연결
     * @return 차단 대상 hash가 하나라도 있으면 {@code true}
     * @throws SQLException 인증 테이블을 조회할 수 없을 때
     */
    private static boolean containsRejectedLegacyCredential(
            Connection connection
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT password
                     FROM public.authentications
                     """)) {
            while (resultSet.next()) {
                String passwordHash = resultSet.getString("password");
                if (passwordHash != null
                        && REJECTED_LEGACY_CREDENTIAL_FINGERPRINTS.contains(
                                fingerprint(passwordHash)
                        )) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * V009가 소유할 zero-argument trigger 함수가 unmanaged schema에 이미 있는지
     * 정확한 PostgreSQL 함수 signature로 확인한다.
     *
     * <p>동일 이름의 다른 overload는 V009와 충돌하지 않으므로 {@code to_regproc}
     * 가 아니라 {@code to_regprocedure}를 사용한다.</p>
     *
     * @param connection 읽기 전용 사전검사 연결
     * @return V009와 충돌하는 함수가 하나라도 있으면 {@code true}
     * @throws SQLException catalog 조회를 수행할 수 없을 때
     */
    private static boolean containsReservedV009Function(
            Connection connection
    ) throws SQLException {
        return queryBoolean(connection, """
                SELECT to_regprocedure(
                           'public.attend_require_member_birth_on_write()'
                       ) IS NOT NULL
                    OR to_regprocedure(
                           'public.attend_require_operational_membership_member()'
                       ) IS NOT NULL
                    OR to_regprocedure(
                           'public.attend_require_closed_card_assignment_immutable()'
                       ) IS NOT NULL
                """);
    }

    /** 아직 적용되지 않은 V009~V012가 생성할 함수와의 충돌 버전을 찾는다. */
    private static String conflictingReservedFunctionVersion(
            Connection connection,
            boolean flywayHistoryExists
    ) throws SQLException {
        if ((!flywayHistoryExists || !isSuccessfulMigrationApplied(connection, "9"))
                && containsReservedV009Function(connection)) {
            return "V009";
        }
        if ((!flywayHistoryExists || !isSuccessfulMigrationApplied(connection, "10"))
                && containsReservedV010Function(connection)) {
            return "V010";
        }
        if ((!flywayHistoryExists || !isSuccessfulMigrationApplied(connection, "11"))
                && containsReservedV011Function(connection)) {
            return "V011";
        }
        if ((!flywayHistoryExists || !isSuccessfulMigrationApplied(connection, "12"))
                && containsReservedV012Function(connection)) {
            return "V012";
        }
        return null;
    }

    private static boolean isSuccessfulMigrationApplied(
            Connection connection,
            String version
    ) throws SQLException {
        if (!Set.of("9", "10", "11", "12").contains(version)) {
            throw new IllegalArgumentException("Unsupported preflight version");
        }
        return queryBoolean(connection, """
                SELECT EXISTS (
                    SELECT 1
                    FROM public.flyway_schema_history
                    WHERE success
                      AND version IN ('%1$s', '0%1$s', '00%1$s')
                )
                """.formatted(version));
    }

    private static boolean containsReservedV010Function(
            Connection connection
    ) throws SQLException {
        return queryBoolean(connection, """
                SELECT to_regprocedure(
                           'public.attend_set_audit_occurred_at()'
                       ) IS NOT NULL
                    OR to_regprocedure(
                           'public.attend_purge_expired_audit_log_batch()'
                       ) IS NOT NULL
                """);
    }

    private static boolean containsReservedV011Function(
            Connection connection
    ) throws SQLException {
        return queryBoolean(connection, """
                SELECT to_regprocedure(
                           'public.attend_set_tag_event_received_at()'
                       ) IS NOT NULL
                    OR to_regprocedure(
                           'public.attend_purge_expired_tag_event_log_batch()'
                       ) IS NOT NULL
                """);
    }

    private static boolean containsReservedV012Function(
            Connection connection
    ) throws SQLException {
        return queryBoolean(connection, """
                SELECT to_regprocedure(
                           'public.attend_purge_expired_telegram_webhook_update_batch()'
                       ) IS NOT NULL
                """);
    }

    /**
     * 민감한 BCrypt 문자열을 직접 denylist에 넣지 않도록 SHA-256 지문을 만든다.
     *
     * <p>이 지문은 비밀번호 검증용이 아니라 이미 공개된 특정 샘플 hash를
     * 식별하기 위한 고정 비교값이다.</p>
     *
     * @param passwordHash 레거시 DB에 저장된 BCrypt 문자열
     * @return 소문자 16진수 SHA-256 지문
     */
    private static String fingerprint(String passwordHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(
                            passwordHash.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime",
                    exception
            );
        }
    }

    /**
     * V001의 첫 검증 블록을 사전검사용 읽기 잠금 버전으로 읽는다.
     *
     * <p>실제 migration 파일은 수정하지 않는다. 메모리에 읽은 복사본에서
     * {@code ACCESS EXCLUSIVE}를 {@code ACCESS SHARE}로 한 번만 바꿔
     * 사전검사가 운영 writer를 불필요하게 막지 않도록 한다.</p>
     *
     * @return 읽기 전용 트랜잭션에서 실행할 V001 검증 블록
     * @throws IllegalStateException 리소스 형식이 예상과 다르거나 읽을 수 없을 때
     */
    private static String loadReadOnlyV001ValidationSql() {
        try {
            String migration = new ClassPathResource(V001_RESOURCE)
                    .getContentAsString(StandardCharsets.UTF_8);
            int markerIndex = migration.indexOf(V001_END_MARKER);
            if (markerIndex < 0) {
                throw new IllegalStateException(
                        "V001 validation block marker is missing"
                );
            }

            String validationBlock = migration.substring(
                    0,
                    markerIndex + V001_END_MARKER.length()
            );
            int lockIndex = validationBlock.indexOf(EXCLUSIVE_LOCK);
            if (lockIndex < 0
                    || validationBlock.indexOf(
                    EXCLUSIVE_LOCK,
                    lockIndex + EXCLUSIVE_LOCK.length()
            ) >= 0) {
                throw new IllegalStateException(
                        "V001 must contain exactly one legacy exclusive lock"
                );
            }
            return validationBlock.replace(EXCLUSIVE_LOCK, READ_ONLY_LOCK);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "V001 migration resource could not be loaded",
                    exception
            );
        }
    }

    /**
     * 결과를 반환하지 않는 SQL 한 문장을 실행한다.
     *
     * @param connection 사용할 JDBC 연결
     * @param sql 실행할 SQL
     * @throws SQLException PostgreSQL이 SQL을 거부할 때
     */
    private static void execute(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 단일 행·단일 정수 결과를 조회한다.
     *
     * @param connection 사용할 JDBC 연결
     * @param sql 한 개의 정수 값을 반환하는 SQL
     * @return 첫 행의 첫 번째 정수 값
     * @throws SQLException 조회에 실패하거나 결과 형식이 올바르지 않을 때
     */
    private static int queryInt(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * 단일 행·단일 boolean 결과를 조회한다.
     *
     * @param connection 사용할 JDBC 연결
     * @param sql 한 개의 boolean 값을 반환하는 SQL
     * @return 첫 행의 첫 번째 boolean 값
     * @throws SQLException 조회에 실패하거나 결과 형식이 올바르지 않을 때
     */
    private static boolean queryBoolean(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

    /**
     * 단일 행·단일 문자열 결과를 조회한다.
     *
     * @param connection 사용할 JDBC 연결
     * @param sql 한 개의 문자열 값을 반환하는 SQL
     * @return 첫 행의 첫 번째 문자열 값. SQL {@code NULL}이면 Java {@code null}
     * @throws SQLException 조회에 실패하거나 결과 형식이 올바르지 않을 때
     */
    private static String queryString(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    /**
     * 읽기 전용 사전검사의 판정 결과다.
     */
    public enum PreflightStatus {
        /**
         * Flyway history와 사용자 업무 객체가 없는 빈 스키마다.
         */
        FRESH,

        /**
         * V001이 허용하는 정확한 네 테이블 레거시 구조다.
         */
        LEGACY_CANDIDATE,

        /**
         * Flyway history가 있어 신규 baseline을 만들면 안 되는 DB다.
         */
        ALREADY_MANAGED,

        /**
         * 구조가 불명확하거나 안전 규칙을 위반해 자동 변경할 수 없는 DB다.
         */
        REJECTED
    }

    /**
     * 사전검사 상태와 사람이 읽을 수 있는 판정 이유를 함께 전달한다.
     *
     * @param status 기계가 분기 처리할 상태
     * @param reason 로그와 운영 확인에 사용할 설명
     */
    public record PreflightResult(
            PreflightStatus status,
            String reason
    ) {

        /**
         * 거부 결과를 일관된 형태로 만든다.
         *
         * @param reason 거부 이유
         * @return {@link PreflightStatus#REJECTED} 상태의 결과
         */
        private static PreflightResult rejected(String reason) {
            return new PreflightResult(PreflightStatus.REJECTED, reason);
        }
    }
}
