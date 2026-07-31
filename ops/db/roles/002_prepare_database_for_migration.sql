-- Database-level bootstrap. Run as the database/schema owner before dbMigrate.
--
-- PostgreSQL grants CREATE on the public schema and TEMP on the database to
-- PUBLIC in common installations. Both are removed so web roles cannot create
-- persistent or temporary objects. migration_owner receives only public-schema
-- DDL; it does not receive superuser, database creation, or role administration.

BEGIN;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

DO $database_privileges$
BEGIN
    EXECUTE pg_catalog.format(
        'REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC',
        pg_catalog.current_database()
    );
    EXECUTE pg_catalog.format(
        'GRANT CONNECT ON DATABASE %I TO migration_owner, app_runtime, cutover_writer, legacy_writer',
        pg_catalog.current_database()
    );
END
$database_privileges$;

GRANT USAGE, CREATE ON SCHEMA public TO migration_owner;
GRANT USAGE ON SCHEMA public
    TO app_runtime, cutover_writer, legacy_writer;
REVOKE CREATE ON SCHEMA public
    FROM app_runtime, cutover_writer, legacy_writer;

-- Objects created while connected as migration_owner start without PUBLIC
-- table/sequence privileges and without the usual PUBLIC function EXECUTE.
ALTER DEFAULT PRIVILEGES FOR ROLE migration_owner IN SCHEMA public
    REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE migration_owner IN SCHEMA public
    REVOKE ALL PRIVILEGES ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE migration_owner IN SCHEMA public
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

COMMIT;
