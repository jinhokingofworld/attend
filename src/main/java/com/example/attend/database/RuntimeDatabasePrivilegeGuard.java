package com.example.attend.database;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 운영 웹 애플리케이션의 실제 DB 계정이 최소 권한인지 기동 전에 검증한다.
 *
 * <p>환경변수의 사용자명이 {@code app_runtime}이라고 적혀 있는지만 확인하면
 * 이름과 실제 권한이 다른 계정을 막을 수 없다. 이 guard는 현재 연결로 PostgreSQL
 * 권한 함수를 직접 호출해 영구·임시 DDL, Flyway history 변경, 교사 물리 삭제,
 * 레거시 테이블 DML과 trigger 함수 직접 실행이 모두 차단됐는지 확인한다.</p>
 */
@Component
@Profile("prod")
public final class RuntimeDatabasePrivilegeGuard implements InitializingBean {

    private final DataSource dataSource;

    /**
     * 운영 데이터소스의 실제 권한을 검사하는 guard를 만든다.
     *
     * @param dataSource 웹 애플리케이션이 사용할 운영 데이터소스
     */
    public RuntimeDatabasePrivilegeGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Spring bean 초기화 중 최소 권한 검사를 실행한다.
     */
    @Override
    public void afterPropertiesSet() {
        verify(dataSource);
    }

    /**
     * 현재 DB 사용자가 V008 runtime 최소 권한 경계를 지키는지 확인한다.
     *
     * @param dataSource 검사할 운영 데이터소스
     * @throws IllegalStateException 권한이 과도하거나 필수 조회 권한이 없을 때
     */
    public static void verify(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT current_schema() = 'public'
                        AND has_schema_privilege(
                            current_user,
                            'public',
                            'USAGE'
                        )
                        AND NOT has_schema_privilege(
                            current_user,
                            'public',
                            'CREATE'
                        )
                        AND NOT has_database_privilege(
                            current_user,
                            current_database(),
                            'TEMP'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.flyway_schema_history',
                            'SELECT'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.flyway_schema_history',
                            'INSERT,UPDATE,DELETE'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.member',
                            'DELETE'
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_set_updated_at()',
                            'EXECUTE'
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM (
                                VALUES
                                    ('authentications'),
                                    ('attendance'),
                                    ('attendance_log')
                            ) AS legacy(table_name)
                            WHERE to_regclass(
                                      'public.' || legacy.table_name
                                  ) IS NOT NULL
                              AND has_table_privilege(
                                  current_user,
                                  'public.' || legacy.table_name,
                                  'INSERT,UPDATE,DELETE'
                              )
                        )
                     """)) {
            resultSet.next();
            if (!resultSet.getBoolean(1)) {
                throw incompatible();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Runtime database privileges could not be verified",
                    exception
            );
        }
    }

    /**
     * 권한 세부정보를 외부에 노출하지 않는 공통 기동 실패 예외를 만든다.
     *
     * @return 최소 권한 위반을 나타내는 예외
     */
    private static IllegalStateException incompatible() {
        return new IllegalStateException(
                "Runtime database privileges are incompatible with this application"
        );
    }
}
