package com.example.attend.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.ALREADY_MANAGED;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.FRESH;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.LEGACY_CANDIDATE;

/**
 * 사전검사부터 최종 버전 확인까지 안전한 Flyway 실행 순서를 강제한다.
 *
 * <p>Flyway를 바로 호출하면 운영 이력이 있는 DB를 빈 DB로 오판해 baseline할
 * 위험이 있다. 이 실행기는 다음 순서를 하나의 고정된 절차로 제공한다.</p>
 *
 * <ol>
 *     <li>DB를 읽기 전용으로 검사한다.</li>
 *     <li>신규·레거시 DB라면 운영 책임자의 승인 분류와 실제 구조를 대조한다.</li>
 *     <li>정확한 레거시 구조에만 version 0 baseline을 명시적으로 기록한다.</li>
     *     <li>V021까지 순서대로 적용하고 checksum을 검증한다.</li>
 *     <li>애플리케이션이 요구하는 버전 목록과 정확히 일치하는지 다시 확인한다.</li>
 * </ol>
 */
public final class DatabaseMigrationRunner {

    /**
     * 이 애플리케이션 버전이 지원하는 유일한 목표 스키마 버전이다.
     */
    public static final MigrationVersion TARGET_VERSION =
            MigrationVersion.fromVersion("21");

    private final DatabasePreflightInspector preflightInspector;

    /**
     * 운영용 사전검사기를 사용하는 실행기를 만든다.
     */
    public DatabaseMigrationRunner() {
        this(new DatabasePreflightInspector());
    }

    /**
     * 테스트에서 사전검사기를 교체할 수 있도록 마련한 패키지 전용 생성자다.
     *
     * @param preflightInspector Flyway 실행 전에 사용할 읽기 전용 검사기
     */
    DatabaseMigrationRunner(
            DatabasePreflightInspector preflightInspector
    ) {
        this.preflightInspector = preflightInspector;
    }

    /**
     * 승인된 원본 DB에 V021까지의 migration을 적용하고 결과를 검증한다.
     *
     * <p>{@link ApprovedSourceClass}는 코드가 데이터의 실제 용도를 추측한 결과가
     * 아니라 운영 책임자가 확인한 값이어야 한다. 신규·레거시 DB에서 승인값과
     * 사전검사 결과가 다르면 Flyway history를 만들기 전에 중단한다. 이미
     * Flyway가 관리하는 DB는 원본 분류 대신 기존 history와 최종 버전을
     * 검증한다.</p>
     *
     * @param dataSource migration 전용 권한으로 연결되는 데이터소스
     * @param approvedSourceClass 운영 책임자가 승인한 원본 DB 분류
     * @throws IllegalStateException DB 구조가 허용되지 않거나 승인 분류와 다르거나,
     *                               최종 스키마 버전이 정확히 일치하지 않을 때
     */
    public void migrate(
            DataSource dataSource,
            ApprovedSourceClass approvedSourceClass
    ) {
        if (approvedSourceClass == ApprovedSourceClass.UNKNOWN) {
            throw new IllegalStateException(
                    "UNKNOWN source classification cannot authorize migration"
            );
        }

        DatabasePreflightInspector.PreflightResult preflight =
                preflightInspector.inspect(dataSource);

        // 사람의 승인값과 기계가 확인한 구조를 둘 다 통과해야 한다.
        if (preflight.status() == FRESH
                && approvedSourceClass != ApprovedSourceClass.NEW_OR_SAMPLE) {
            throw new IllegalStateException(
                    "A fresh database requires NEW_OR_SAMPLE approval"
            );
        }
        if (preflight.status() == LEGACY_CANDIDATE
                && approvedSourceClass
                != ApprovedSourceClass.LEGACY_OPERATIONAL) {
            throw new IllegalStateException(
                    "A legacy database requires LEGACY_OPERATIONAL approval"
            );
        }
        if (preflight.status() != FRESH
                && preflight.status() != LEGACY_CANDIDATE
                && preflight.status() != ALREADY_MANAGED) {
            throw new IllegalStateException(
                    "Database migration preflight rejected this database: "
                            + preflight.reason()
            );
        }

        verifyMigrationPrivileges(dataSource, preflight.status());

        Flyway flyway = Flyway.configure()
                .configuration(Map.of(
                        "flyway.postgresql.transactional.lock", "false"))
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .schemas("public")
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .target(TARGET_VERSION)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .outOfOrder(false)
                .load();

        // baseline은 레거시 테이블을 보존하면서 Flyway 관리 시작점을 기록할 때만 쓴다.
        if (preflight.status() == LEGACY_CANDIDATE) {
            flyway.baseline();
        }

        // migrate 뒤 validate와 런타임 검사를 모두 수행해 누락·초과 버전을 막는다.
        flyway.migrate();
        flyway.validate();
        SchemaVersionGuard.verify(dataSource);
    }

    /**
     * migration 연결이 스키마를 변경할 권한과 레거시 객체 소유권을 가졌는지 확인한다.
     *
     * <p>특히 레거시 경로는 {@code baseline()}이 먼저 별도 commit된다. 객체를
     * 변경할 권한이 없는 연결로 baseline부터 기록하면 V001 실패 뒤에 잘못된
     * version 0 history만 남을 수 있으므로, 실제 쓰기 전에 권한을 읽기 전용으로
     * 검사한다.</p>
     *
     * @param dataSource migration 전용 데이터소스
     * @param status 읽기 전용 사전검사가 판정한 DB 상태
     * @throws IllegalStateException schema 변경 권한이 없거나 레거시 객체를
     *                               소유한 역할의 구성원이 아닐 때
     */
    private static void verifyMigrationPrivileges(
            DataSource dataSource,
            DatabasePreflightInspector.PreflightStatus status
    ) {
        boolean canCreateInPublic = queryBoolean(dataSource, """
                SELECT has_schema_privilege(
                           current_user,
                           'public',
                           'USAGE'
                       )
                   AND has_schema_privilege(
                           current_user,
                           'public',
                           'CREATE'
                       )
                """);
        if (!canCreateInPublic) {
            throw new IllegalStateException(
                    "Migration connection cannot create objects in public schema"
            );
        }

        if (status != LEGACY_CANDIDATE) {
            return;
        }

        boolean ownsLegacyObjects = queryBoolean(dataSource, """
                WITH required_owners AS (
                    SELECT relation.relowner AS owner_oid
                    FROM pg_catalog.pg_class AS relation
                    JOIN pg_catalog.pg_namespace AS namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = 'public'
                      AND relation.relname = ANY (
                        ARRAY[
                          'member',
                          'authentications',
                          'attendance',
                          'attendance_log',
                          'member_id_seq',
                          'attendance_attend_id_seq',
                          'attendance_log_id_seq'
                        ]
                      )
                      AND relation.relkind IN ('r', 'S')

                    UNION ALL

                    SELECT data_type.typowner AS owner_oid
                    FROM pg_catalog.pg_type AS data_type
                    JOIN pg_catalog.pg_namespace AS namespace
                      ON namespace.oid = data_type.typnamespace
                    WHERE namespace.nspname = 'public'
                      AND data_type.typname = ANY (
                        ARRAY['role', 'attend_status']
                      )
                      AND data_type.typtype = 'e'
                )
                SELECT count(*) = 9
                   AND bool_and(
                       pg_catalog.pg_has_role(
                           current_user,
                           owner_oid,
                           'MEMBER'
                       )
                   )
                FROM required_owners
                """);
        if (!ownsLegacyObjects) {
            throw new IllegalStateException(
                    "Migration connection does not own all accepted legacy objects"
            );
        }
    }

    /**
     * migration 안전조건을 판정하는 단일 boolean SQL을 실행한다.
     *
     * @param dataSource 검사할 데이터소스
     * @param sql 한 행·한 boolean 열을 반환하는 SQL
     * @return 첫 행의 boolean 결과
     * @throws IllegalStateException 권한 검사를 완료할 수 없을 때
     */
    private static boolean queryBoolean(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getBoolean(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Migration privilege preflight could not be completed (SQLSTATE "
                            + exception.getSQLState() + ")");
        }
    }

    /**
     * 운영 책임자가 승인할 수 있는 원본 DB 유형이다.
     */
    public enum ApprovedSourceClass {
        /**
         * 보존할 운영 데이터가 없어 새 빈 DB에서 시작하는 경우다.
         */
        NEW_OR_SAMPLE,

        /**
         * 기존 네 개 레거시 테이블과 데이터를 보존해야 하는 운영 DB다.
         */
        LEGACY_OPERATIONAL,

        /**
         * 실제 용도를 확인하지 못해 어떤 migration도 승인할 수 없는 DB다.
         */
        UNKNOWN
    }
}
