package com.example.attend.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import javax.sql.DataSource;

import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.ALREADY_MANAGED;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.FRESH;
import static com.example.attend.database.DatabasePreflightInspector.PreflightStatus.LEGACY_CANDIDATE;

public final class DatabaseMigrationRunner {

    public static final MigrationVersion TARGET_VERSION =
            MigrationVersion.fromVersion("8");

    private final DatabasePreflightInspector preflightInspector;

    public DatabaseMigrationRunner() {
        this(new DatabasePreflightInspector());
    }

    DatabaseMigrationRunner(
            DatabasePreflightInspector preflightInspector
    ) {
        this.preflightInspector = preflightInspector;
    }

    public void migrate(
            DataSource dataSource,
            ApprovedSourceClass approvedSourceClass
    ) {
        DatabasePreflightInspector.PreflightResult preflight =
                preflightInspector.inspect(dataSource);

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

        Flyway flyway = Flyway.configure()
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

        if (preflight.status() == LEGACY_CANDIDATE) {
            flyway.baseline();
        }

        flyway.migrate();
        flyway.validate();
        SchemaVersionGuard.verify(dataSource);
    }

    public enum ApprovedSourceClass {
        NEW_OR_SAMPLE,
        LEGACY_OPERATIONAL
    }
}
