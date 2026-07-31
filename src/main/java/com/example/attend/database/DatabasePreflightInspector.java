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

    public DatabasePreflightInspector() {
        this.readOnlyV001ValidationSql = loadReadOnlyV001ValidationSql();
    }

    public PreflightResult inspect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "SET TRANSACTION READ ONLY");

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

                if (flywayHistoryExists) {
                    return new PreflightResult(
                            PreflightStatus.ALREADY_MANAGED,
                            "Flyway history already exists"
                    );
                }

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
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Database preflight could not be completed",
                    exception
            );
        }
    }

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

    private static void execute(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int queryInt(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

    private static String queryString(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    public enum PreflightStatus {
        FRESH,
        LEGACY_CANDIDATE,
        ALREADY_MANAGED,
        REJECTED
    }

    public record PreflightResult(
            PreflightStatus status,
            String reason
    ) {

        private static PreflightResult rejected(String reason) {
            return new PreflightResult(PreflightStatus.REJECTED, reason);
        }
    }
}
