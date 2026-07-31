-- Cluster-level bootstrap. Run once as a PostgreSQL role administrator.
--
-- The four roles are login identities but this script never assigns passwords.
-- Provision credentials through the deployment secret store, not this repository.
-- Re-running the script fails closed if an existing role has elevated attributes.

BEGIN;

DO $role_bootstrap$
DECLARE
    role_name TEXT;
BEGIN
    FOREACH role_name IN ARRAY ARRAY[
        'migration_owner',
        'app_runtime',
        'cutover_writer',
        'legacy_writer'
    ]
    LOOP
        IF NOT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_roles
            WHERE rolname = role_name
        ) THEN
            EXECUTE pg_catalog.format(
                'CREATE ROLE %I LOGIN NOSUPERUSER INHERIT NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
                role_name
            );
        ELSIF EXISTS (
            SELECT 1
            FROM pg_catalog.pg_roles
            WHERE rolname = role_name
              AND (
                  NOT rolcanlogin
                  OR rolsuper
                  OR rolcreatedb
                  OR rolcreaterole
                  OR rolreplication
                  OR rolbypassrls
              )
        ) THEN
            RAISE EXCEPTION
                'Role % exists with attributes outside the Attend role contract',
                role_name;
        END IF;
    END LOOP;
END
$role_bootstrap$;

COMMIT;
