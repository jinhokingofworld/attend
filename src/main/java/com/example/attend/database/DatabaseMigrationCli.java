package com.example.attend.database;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

public final class DatabaseMigrationCli {

    private DatabaseMigrationCli() {
    }

    public static void main(String[] args) {
        Map<String, String> environment = System.getenv();
        String url = required(environment, "FLYWAY_DB_URL");
        String username = required(environment, "FLYWAY_DB_USERNAME");
        String password = required(environment, "FLYWAY_DB_PASSWORD");
        DatabaseMigrationRunner.ApprovedSourceClass sourceClass =
                DatabaseMigrationRunner.ApprovedSourceClass.valueOf(
                        required(environment, "MIGRATION_SOURCE_CLASS")
                );

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(url, username, password);
        new DatabaseMigrationRunner().migrate(dataSource, sourceClass);

        System.out.println("Database migration validated at target V008.");
    }

    private static String required(
            Map<String, String> environment,
            String name
    ) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " must be supplied outside the application artifact"
            );
        }
        return value;
    }
}
