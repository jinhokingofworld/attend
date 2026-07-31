package com.example.attend.database;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("prod")
public final class SchemaVersionGuard implements InitializingBean {

    private static final List<MigrationVersion> REQUIRED_VERSIONS =
            List.of(
                    "1", "2", "3", "4", "5", "6", "7", "8"
            ).stream()
                    .map(MigrationVersion::fromVersion)
                    .toList();

    private final DataSource dataSource;

    public SchemaVersionGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        verify(dataSource);
    }

    public static void verify(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT version, type, success
                     FROM public.flyway_schema_history
                     ORDER BY installed_rank
                     """)) {
            List<MigrationVersion> appliedVersions = new ArrayList<>();
            while (resultSet.next()) {
                if (!resultSet.getBoolean("success")) {
                    throw incompatible();
                }
                String version = resultSet.getString("version");
                String type = resultSet.getString("type");
                if (version != null && !"BASELINE".equals(type)) {
                    appliedVersions.add(
                            MigrationVersion.fromVersion(version)
                    );
                }
            }

            if (!REQUIRED_VERSIONS.equals(appliedVersions)) {
                throw incompatible();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Required Flyway schema history is unavailable",
                    exception
            );
        }
    }

    private static IllegalStateException incompatible() {
        return new IllegalStateException(
                "Database schema is incompatible with this application release"
        );
    }
}
