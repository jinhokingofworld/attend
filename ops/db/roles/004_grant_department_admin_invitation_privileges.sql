-- Run after 003_grant_application_privileges.sql and successful Flyway V018.
-- This stays separate so V017 validation and rollback checks keep their exact
-- expected boundary.
BEGIN;

DO $required_v018$
BEGIN
    IF pg_catalog.to_regclass('public.department_admin_invitation_outbox') IS NULL
       OR NOT EXISTS (
           SELECT 1 FROM public.flyway_schema_history
           WHERE version = '018' AND success
       ) THEN
        RAISE EXCEPTION 'Department invitation grants require successful Flyway migration V018';
    END IF;
END
$required_v018$;

GRANT SELECT, INSERT ON TABLE public.department_admin_invitation_outbox
TO app_runtime, cutover_writer;

GRANT UPDATE (
    status,
    attempt_count,
    claim_version,
    next_attempt_at,
    lease_until,
    sent_at,
    last_error_code,
    updated_at
) ON TABLE public.department_admin_invitation_outbox
TO app_runtime, cutover_writer;

GRANT UPDATE (name, active) ON TABLE public.department
TO app_runtime, cutover_writer;

GRANT USAGE ON SEQUENCE public.department_admin_invitation_outbox_id_seq
TO app_runtime, cutover_writer;

COMMIT;
