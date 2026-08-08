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
						WITH current_role_identity AS (
						    SELECT role.oid
						    FROM pg_catalog.pg_roles AS role
						    WHERE role.rolname = current_user
						), non_system_namespace AS (
						    SELECT namespace.oid, namespace.nspname
						    FROM pg_catalog.pg_namespace AS namespace
						    WHERE namespace.nspname NOT IN (
						        'pg_catalog',
						        'information_schema',
						        'pg_toast'
						    )
						      AND namespace.nspname NOT LIKE 'pg_temp_%'
						      AND namespace.nspname NOT LIKE 'pg_toast_temp_%'
						)
						SELECT current_schema() = 'public'
						   AND current_user = session_user
						   AND has_schema_privilege(current_user, 'public', 'USAGE')
						   AND NOT has_schema_privilege(current_user, 'public', 'CREATE')
						   AND NOT EXISTS (
						       SELECT 1
						       FROM non_system_namespace AS namespace
						       WHERE namespace.nspname <> 'public'
						         AND (
						             has_schema_privilege(
						                 current_user, namespace.oid, 'USAGE'
						             )
						             OR has_schema_privilege(
						                 current_user, namespace.oid, 'CREATE'
						             )
						         )
						   )
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
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_shdepend AS dependency
						       CROSS JOIN current_role_identity AS role
						       WHERE dependency.refclassid =
						                 'pg_catalog.pg_authid'::regclass
						         AND dependency.refobjid = role.oid
						         AND dependency.deptype = 'o'
						         AND dependency.dbid IN (
						             0,
						             (
						                 SELECT database.oid
						                 FROM pg_catalog.pg_database AS database
						                 WHERE database.datname = current_database()
						             )
						         )
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
						   AND has_function_privilege(
						       current_user,
						       'public.attend_purge_expired_telegram_webhook_update_batch()',
						       'EXECUTE'
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_class AS relation
						       JOIN non_system_namespace AS namespace
						         ON namespace.oid = relation.relnamespace
						       WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
						         AND has_table_privilege(
						             current_user,
						             relation.oid,
						             'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'
						         )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_class AS relation
						       JOIN non_system_namespace AS namespace
						         ON namespace.oid = relation.relnamespace
						       JOIN pg_catalog.pg_attribute AS attribute
						         ON attribute.attrelid = relation.oid
						       WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
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
						       JOIN non_system_namespace AS namespace
						         ON namespace.oid = sequence.relnamespace
						       WHERE sequence.relkind = 'S'
						         AND has_sequence_privilege(
						             current_user,
						             sequence.oid,
						             'USAGE,SELECT,UPDATE'
						         )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_proc AS function
						       JOIN non_system_namespace AS namespace
						         ON namespace.oid = function.pronamespace
						       CROSS JOIN current_role_identity AS role
						       WHERE function.oid NOT IN (
						           'public.attend_purge_expired_audit_log_batch()'
						               ::regprocedure,
						           'public.attend_purge_expired_tag_event_log_batch()'
						               ::regprocedure,
						           'public.attend_purge_expired_telegram_webhook_update_batch()'
						               ::regprocedure
						         )
						         AND (
						             (
						                 namespace.nspname = 'public'
						                 AND has_function_privilege(
						                     current_user, function.oid, 'EXECUTE'
						                 )
						             )
						             OR EXISTS (
						                 SELECT 1
						                 FROM pg_catalog.aclexplode(
						                     function.proacl
						                 ) AS privilege
						                 WHERE privilege.grantee = role.oid
						                   AND privilege.privilege_type = 'EXECUTE'
						             )
						         )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_type AS type
						       JOIN non_system_namespace AS namespace
						         ON namespace.oid = type.typnamespace
						       CROSS JOIN current_role_identity AS role
						       WHERE EXISTS (
						           SELECT 1
						           FROM pg_catalog.aclexplode(type.typacl)
						               AS privilege
						           WHERE privilege.grantee = role.oid
						       )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_largeobject_metadata AS large_object
						       CROSS JOIN current_role_identity AS role
						       WHERE large_object.lomowner = role.oid
						          OR EXISTS (
						              SELECT 1
						              FROM pg_catalog.aclexplode(
						                  large_object.lomacl
						              ) AS privilege
						              WHERE privilege.grantee IN (0, role.oid)
						                AND privilege.privilege_type IN (
						                    'SELECT', 'UPDATE'
						                )
						          )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_tablespace AS tablespace
						       WHERE has_tablespace_privilege(
						           current_user, tablespace.oid, 'CREATE'
						       )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_foreign_data_wrapper AS wrapper
						       WHERE has_foreign_data_wrapper_privilege(
						           current_user, wrapper.oid, 'USAGE'
						       )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_foreign_server AS server
						       WHERE has_server_privilege(
						           current_user, server.oid, 'USAGE'
						       )
						   )
						   AND NOT EXISTS (
						       SELECT 1
						       FROM pg_catalog.pg_default_acl AS defaults
						       CROSS JOIN current_role_identity AS role
						       WHERE defaults.defaclrole = role.oid
						          OR EXISTS (
						              SELECT 1
						              FROM pg_catalog.aclexplode(
						                  defaults.defaclacl
						              ) AS privilege
						              WHERE privilege.grantee = role.oid
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
