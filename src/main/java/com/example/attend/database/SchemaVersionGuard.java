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

/**
 * 운영 애플리케이션과 DB 스키마 버전이 정확히 맞는지 시작 전에 확인한다.
 *
 * <p>운영 웹 프로세스에서는 Flyway를 비활성화하므로 애플리케이션이 스스로
 * migration을 적용하지 않는다. 대신 이 컴포넌트가 빈 history, 실패한 migration,
 * 누락 버전과 애플리케이션보다 앞선 버전을 모두 거부한다. 따라서 잘못된
 * 스키마에서 요청을 처리하는 것보다 애플리케이션 기동을 실패시키는 쪽을
 * 선택한다.</p>
 */
@Component
@Profile("prod")
public final class SchemaVersionGuard implements InitializingBean {

    private static final int QUERY_TIMEOUT_SECONDS = 3;
    private static final List<MigrationVersion> REQUIRED_VERSIONS =
            List.of(
                    "1", "2", "3", "4", "5", "6", "7",
                    "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19"
            ).stream()
                    .map(MigrationVersion::fromVersion)
                    .toList();

    private final DataSource dataSource;

    /**
     * 운영 데이터소스를 검사하는 guard를 만든다.
     *
     * @param dataSource 읽기 권한이 있는 애플리케이션 데이터소스
     */
    public SchemaVersionGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Spring bean 초기화 과정에서 스키마 검사를 즉시 실행한다.
     *
     * <p>{@code ApplicationRunner}보다 이 시점을 사용하는 이유는 웹 서버가
     * 정상 기동됐다고 보이기 전에 호환되지 않는 DB를 차단하기 위해서다.</p>
     */
    @Override
    public void afterPropertiesSet() {
        verify(dataSource);
    }

    /**
     * 성공한 versioned migration이 V001~V018과 정확히 일치하는지 확인한다.
     *
     * <p>문자열의 최댓값만 비교하면 {@code 9}와 {@code 10} 같은 버전을
     * 잘못 정렬할 수 있으므로 Flyway의 {@link MigrationVersion}으로 해석한
     * 전체 순서를 비교한다. 레거시 도입용 baseline 0은 버전 목록에서 제외한다.</p>
     *
     * @param dataSource {@code flyway_schema_history}를 읽을 데이터소스
     * @throws IllegalStateException history를 읽을 수 없거나 성공 버전 목록이
     *                               애플리케이션 요구사항과 다를 때
     */
    public static void verify(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery("""
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
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Required Flyway schema history is unavailable",
                    exception
            );
        }
    }

    /**
     * 모든 스키마 불일치 경로에서 사용할 동일한 예외를 만든다.
     *
     * @return 외부에 DB 세부 구조를 노출하지 않는 기동 실패 예외
     */
    private static IllegalStateException incompatible() {
        return new IllegalStateException(
                "Database schema is incompatible with this application release"
        );
    }
}
