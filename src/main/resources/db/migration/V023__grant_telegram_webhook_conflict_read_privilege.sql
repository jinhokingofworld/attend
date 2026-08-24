-- INSERT ... ON CONFLICT (update_id) DO NOTHING reads the arbiter column.
-- PostgreSQL therefore requires SELECT on update_id in addition to INSERT.
-- Grant only that column to the runtime identities instead of table-wide SELECT.
DO $grant_telegram_webhook_conflict_read_privilege$
DECLARE
    role_name TEXT;
BEGIN
    FOR role_name IN
        SELECT rolname
        FROM pg_catalog.pg_roles
        WHERE rolname IN ('app_runtime', 'cutover_writer')
    LOOP
        EXECUTE format(
            'GRANT SELECT (update_id) ON TABLE public.telegram_webhook_update TO %I',
            role_name
        );
    END LOOP;
END
$grant_telegram_webhook_conflict_read_privilege$;
