package com.example.attend.database;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

/**
 * 웹 애플리케이션과 분리된 데이터베이스 마이그레이션 명령행 진입점이다.
 *
 * <p>운영 웹 프로세스에는 스키마 변경 권한을 주지 않는다. 대신 배포 작업이
 * {@code dbMigrate} Gradle 작업을 실행하면 이 클래스가 direct DB 접속정보와
 * 승인된 원본 DB 분류를 환경변수에서 읽고 {@link DatabaseMigrationRunner}를
 * 호출한다.</p>
 *
 * <p>비밀번호를 명령행 인자로 받지 않는 이유는 프로세스 목록이나 셸 기록에
 * 자격증명이 노출되는 것을 피하기 위해서다.</p>
 */
public final class DatabaseMigrationCli {

    /**
     * 유틸리티 진입점 클래스의 인스턴스 생성을 막는다.
     */
    private DatabaseMigrationCli() {
    }

    /**
     * 필수 환경변수를 읽어 승인된 Flyway 마이그레이션을 실행한다.
     *
     * @param args 사용하지 않는다. 민감정보는 명령행 인자가 아닌 환경변수로 받는다.
     * @throws IllegalStateException 필수 환경변수가 없거나 비어 있을 때
     * @throws IllegalArgumentException {@code MIGRATION_SOURCE_CLASS} 값이
     *                                  허용된 enum 값이 아닐 때
     */
    public static void main(String[] args) {
        Map<String, String> environment = System.getenv();
        MigrationDatabaseConnection connection =
                MigrationDatabaseConnection.from(environment);
        DatabaseMigrationRunner.ApprovedSourceClass sourceClass =
                DatabaseMigrationRunner.ApprovedSourceClass.valueOf(
                        required(environment, "MIGRATION_SOURCE_CLASS")
                );

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        connection.url(),
                        connection.username(),
                        connection.password());
        new DatabaseMigrationRunner().migrate(dataSource, sourceClass);

        System.out.println("Database migration validated at target V017.");
    }

    /**
     * 환경변수 하나를 가져오고 사용할 수 있는 값인지 검사한다.
     *
     * @param environment 프로세스 환경변수 맵
     * @param name 읽을 환경변수 이름
     * @return 공백이 아닌 환경변수 값
     * @throws IllegalStateException 변수가 없거나 공백뿐일 때
     */
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
