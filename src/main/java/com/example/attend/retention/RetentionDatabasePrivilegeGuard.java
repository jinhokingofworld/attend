package com.example.attend.retention;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** retention 연결이 두 고정 purge 함수만 실행할 수 있는지 fail-closed로 검사한다. */
public final class RetentionDatabasePrivilegeGuard {

	private RetentionDatabasePrivilegeGuard() {
	}

	/** 현재 연결 사용자의 실제·상속 권한과 역할 속성을 함께 검사한다. */
	public static void verify(Connection connection) {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						SELECT current_schema() = 'public'
						   AND current_user = session_user
						   AND has_schema_privilege(current_user, 'public', 'USAGE')
						   AND NOT has_schema_privilege(current_user, 'public', 'CREATE')
						   AND NOT has_database_privilege(
						       current_user, current_database(), 'TEMP'
						   )
						   AND NOT has_database_privilege(
						       current_user, current_database(), 'CREATE'
						   )
						   AND EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_roles AS role
						       WHERE role.rolname = current_user
						         AND role.rolcanlogin
						         AND NOT role.rolsuper
						         AND NOT role.rolcreatedb
						         AND NOT role.rolcreaterole
						         AND NOT role.rolreplication
						         AND NOT role.rolbypassrls
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_auth_members AS membership
						       JOIN pg_catalog.pg_roles AS member_role
						         ON member_role.oid = membership.member
						       WHERE member_role.rolname = current_user
						   )
						   AND has_function_privilege(
						       current_user,
						       'public.attend_purge_expired_audit_log_batch()',
						       'EXECUTE'
						   )
						   AND has_function_privilege(
						       current_user,
						       'public.attend_purge_expired_tag_event_log_batch()',
						       'EXECUTE'
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_class AS relation
						       JOIN pg_catalog.pg_namespace AS namespace
						         ON namespace.oid = relation.relnamespace
						       WHERE namespace.nspname = 'public'
						         AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
						         AND has_table_privilege(
						             current_user,
						             relation.oid,
						             'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'
						         )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_class AS relation
						       JOIN pg_catalog.pg_namespace AS namespace
						         ON namespace.oid = relation.relnamespace
						       JOIN pg_catalog.pg_attribute AS attribute
						         ON attribute.attrelid = relation.oid
						       WHERE namespace.nspname = 'public'
						         AND attribute.attnum > 0
						         AND NOT attribute.attisdropped
						         AND has_column_privilege(
						             current_user,
						             relation.oid,
						             attribute.attnum,
						             'SELECT,INSERT,UPDATE,REFERENCES'
						         )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_class AS sequence
						       JOIN pg_catalog.pg_namespace AS namespace
						         ON namespace.oid = sequence.relnamespace
						       WHERE namespace.nspname = 'public'
						         AND sequence.relkind = 'S'
						         AND has_sequence_privilege(
						             current_user,
						             sequence.oid,
						             'USAGE,SELECT,UPDATE'
						         )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_proc AS function
						       JOIN pg_catalog.pg_namespace AS namespace
						         ON namespace.oid = function.pronamespace
						       WHERE namespace.nspname = 'public'
						         AND function.oid NOT IN (
						             'public.attend_purge_expired_audit_log_batch()'
						                 ::regprocedure,
						             'public.attend_purge_expired_tag_event_log_batch()'
						                 ::regprocedure
						         )
						         AND has_function_privilege(
						             current_user, function.oid, 'EXECUTE'
						         )
						   )
						""")) {
			if (!resultSet.next() || !resultSet.getBoolean(1)) {
				throw incompatible();
			}
		} catch (SQLException exception) {
			throw new IllegalStateException(
					"Retention database privileges could not be verified",
					exception);
		}
	}

	private static IllegalStateException incompatible() {
		return new IllegalStateException(
				"Retention database privileges are incompatible with this worker");
	}
}
