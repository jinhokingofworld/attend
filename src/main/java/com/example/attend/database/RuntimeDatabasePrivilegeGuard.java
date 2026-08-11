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
 * 레거시 테이블 DML, {@code member} 금지 컬럼과 trigger 함수 직접 실행이 모두
 * 차단됐는지 확인한다.</p>
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
     * 현재 DB 사용자가 V014 runtime 최소 권한 경계를 지키는지 확인한다.
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
                        AND NOT has_table_privilege(
                            current_user,
                            'public.member',
                            'SELECT'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.member',
                            'INSERT'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.member',
                            'UPDATE'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.member',
                            'TRUNCATE'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.member',
                            'REFERENCES'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.member',
                            'TRIGGER'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.audit_log',
                            'SELECT'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.audit_log',
                            'INSERT'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.audit_log',
                            'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.tag_event_log',
                            'SELECT'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.tag_event_log',
                            'INSERT'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.tag_event_log',
                            'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'
                        )
                        AND NOT has_column_privilege(
                            current_user,
                            'public.tag_event_log',
                            'received_at',
                            'UPDATE'
                        )
                        AND NOT has_table_privilege(
                            current_user,
                            'public.tag_event_log',
                            'DELETE,TRUNCATE,REFERENCES,TRIGGER'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.telegram_link_token',
                            'SELECT,INSERT'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.account_telegram_connection',
                            'SELECT,INSERT,DELETE'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.telegram_webhook_update',
                            'INSERT'
                        )
                        AND has_table_privilege(
                            current_user,
                            'public.attendance_notification_outbox',
                            'SELECT,INSERT'
                        )
                        AND has_sequence_privilege(
                            current_user,
                            'public.telegram_link_token_id_seq',
                            'USAGE'
                        )
                        AND has_sequence_privilege(
                            current_user,
                            'public.attendance_notification_outbox_id_seq',
                            'USAGE'
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM (
                                VALUES
                                    ('attendance_day', 'finalization_due_at'),
                                    ('attendance_day', 'finalization_failure_count'),
                                    ('attendance_day', 'finalization_next_attempt_at'),
                                    ('attendance_day', 'finalization_claim_version'),
                                    ('attendance_day', 'finalization_lease_until'),
                                    ('attendance_day', 'finalization_last_error_code'),
                                    ('attendance_day', 'finalization_last_failed_at'),
                                    ('telegram_link_token', 'consumed_at'),
                                    ('telegram_link_token', 'revoked_at'),
                                    ('account_telegram_connection', 'chat_id'),
                                    ('account_telegram_connection', 'telegram_user_id'),
                                    ('account_telegram_connection', 'updated_at'),
                                    ('attendance_notification_outbox', 'status'),
                                    ('attendance_notification_outbox', 'attempt_count'),
                                    ('attendance_notification_outbox', 'claim_version'),
                                    ('attendance_notification_outbox', 'next_attempt_at'),
                                    ('attendance_notification_outbox', 'lease_until'),
                                    ('attendance_notification_outbox', 'telegram_message_id'),
                                    ('attendance_notification_outbox', 'sent_at'),
                                    ('attendance_notification_outbox', 'last_error_code'),
                                    ('attendance_notification_outbox', 'updated_at')
                            ) AS required(table_name, column_name)
                            WHERE NOT has_column_privilege(
                                current_user,
                                'public.' || required.table_name,
                                required.column_name,
                                'UPDATE'
                            )
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM (
                                VALUES
                                    ('id', 'SELECT'),
                                    ('name', 'SELECT'),
                                    ('phone', 'SELECT'),
                                    ('birth', 'SELECT'),
                                    ('created_at', 'SELECT'),
                                    ('active', 'SELECT'),
                                    ('updated_at', 'SELECT'),
                                    ('name', 'INSERT'),
                                    ('phone', 'INSERT'),
                                    ('birth', 'INSERT'),
                                    ('active', 'INSERT'),
                                    ('name', 'UPDATE'),
                                    ('phone', 'UPDATE'),
                                    ('birth', 'UPDATE'),
                                    ('active', 'UPDATE')
                            ) AS required(column_name, privilege_type)
                            WHERE NOT has_column_privilege(
                                current_user,
                                'public.member',
                                required.column_name,
                                required.privilege_type
                            )
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM (
                                VALUES
                                    ('id', 'INSERT'),
                                    ('id', 'UPDATE'),
                                    ('age', 'SELECT'),
                                    ('age', 'INSERT'),
                                    ('age', 'UPDATE'),
                                    ('created_at', 'INSERT'),
                                    ('created_at', 'UPDATE'),
                                    ('card_uid', 'SELECT'),
                                    ('card_uid', 'INSERT'),
                                    ('card_uid', 'UPDATE'),
                                    ('updated_at', 'INSERT'),
                                    ('updated_at', 'UPDATE')
                            ) AS forbidden(column_name, privilege_type)
                            WHERE has_column_privilege(
                                current_user,
                                'public.member',
                                forbidden.column_name,
                                forbidden.privilege_type
                            )
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM (
                                VALUES
                                    ('department_membership', 'UPDATE'),
                                    ('department_membership', 'DELETE'),
                                    ('department_membership', 'TRUNCATE'),
                                    ('department_membership', 'REFERENCES'),
                                    ('department_membership', 'TRIGGER'),
                                    ('nfc_card_assignment', 'UPDATE'),
                                    ('nfc_card_assignment', 'DELETE'),
                                    ('nfc_card_assignment', 'TRUNCATE'),
                                    ('nfc_card_assignment', 'REFERENCES'),
                                    ('nfc_card_assignment', 'TRIGGER')
                            ) AS forbidden(table_name, privilege_type)
                            WHERE has_table_privilege(
                                current_user,
                                'public.' || forbidden.table_name,
                                forbidden.privilege_type
                            )
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM (
                                VALUES
                                    ('department_membership', 'ended_at'),
                                    ('department_membership', 'ended_by_account_id'),
                                    ('department_membership', 'end_reason'),
                                    ('nfc_card_assignment', 'unassigned_by_account_id'),
                                    ('nfc_card_assignment', 'unassigned_at'),
                                    ('nfc_card_assignment', 'end_reason')
                            ) AS required(table_name, column_name)
                            WHERE NOT has_column_privilege(
                                current_user,
                                'public.' || required.table_name,
                                required.column_name,
                                'UPDATE'
                            )
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM (
                                VALUES
                                    ('department_membership', 'id'),
                                    ('department_membership', 'department_id'),
                                    ('department_membership', 'member_id'),
                                    ('department_membership', 'joined_at'),
                                    ('department_membership', 'created_by_account_id'),
                                    ('nfc_card_assignment', 'id'),
                                    ('nfc_card_assignment', 'nfc_card_id'),
                                    ('nfc_card_assignment', 'department_id'),
                                    ('nfc_card_assignment', 'membership_id'),
                                    ('nfc_card_assignment', 'member_id'),
                                    ('nfc_card_assignment', 'assigned_by_account_id'),
                                    ('nfc_card_assignment', 'assigned_at')
                            ) AS forbidden(table_name, column_name)
                            WHERE has_column_privilege(
                                current_user,
                                'public.' || forbidden.table_name,
                                forbidden.column_name,
                                'UPDATE'
                            )
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_set_updated_at()',
                            'EXECUTE'
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_require_member_birth_on_write()',
                            'EXECUTE'
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_require_operational_membership_member()',
                            'EXECUTE'
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_require_closed_card_assignment_immutable()',
                            'EXECUTE'
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_set_audit_occurred_at()',
                            'EXECUTE'
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_set_tag_event_received_at()',
                            'EXECUTE'
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_purge_expired_audit_log_batch()',
                            'EXECUTE'
                        )
                        AND NOT has_function_privilege(
                            current_user,
                            'public.attend_purge_expired_tag_event_log_batch()',
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
