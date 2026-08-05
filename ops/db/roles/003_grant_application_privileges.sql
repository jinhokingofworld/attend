-- Post-migration grants for the V011 schema. Run as migration_owner or an
-- equivalent owner after guarded dbMigrate succeeds.
--
-- This script is intentionally explicit. A future migration that adds a table,
-- sequence, or writable column must update this file and its privilege tests.

BEGIN;

DO $required_schema$
DECLARE
    missing_tables TEXT;
BEGIN
    SELECT pg_catalog.string_agg(expected.table_name, ', ' ORDER BY expected.table_name)
      INTO missing_tables
      FROM (
          VALUES
              ('flyway_schema_history'),
              ('member'),
              ('department'),
              ('account'),
              ('account_credential_token'),
              ('account_department_role'),
              ('department_membership'),
              ('nfc_card'),
              ('nfc_card_assignment'),
              ('device'),
              ('attendance_policy_version'),
              ('attendance_band'),
              ('attendance_day'),
              ('attendance_target'),
              ('attendance_record'),
              ('tag_event_log'),
              ('audit_log')
      ) AS expected(table_name)
     WHERE pg_catalog.to_regclass(
               'public.' || expected.table_name
           ) IS NULL;

    IF missing_tables IS NOT NULL THEN
        RAISE EXCEPTION
            'Runtime grants require the complete V011 schema; missing: %',
            missing_tables;
    END IF;

    IF pg_catalog.to_regprocedure(
            'public.attend_purge_expired_audit_log_batch()'
       ) IS NULL THEN
        RAISE EXCEPTION
            'Runtime grants require the V011 audit retention function';
    END IF;

    IF pg_catalog.to_regprocedure(
            'public.attend_purge_expired_tag_event_log_batch()'
       ) IS NULL THEN
        RAISE EXCEPTION
            'Runtime grants require the V011 tag-event retention function';
    END IF;
END
$required_schema$;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public
    FROM app_runtime, cutover_writer, legacy_writer, retention_worker;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public
    FROM app_runtime, cutover_writer, legacy_writer, retention_worker;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public
    FROM app_runtime, cutover_writer, legacy_writer, retention_worker;

-- Table-level REVOKE does not remove older column-level grants.
REVOKE ALL PRIVILEGES (
    id,
    name,
    age,
    phone,
    birth,
    created_at,
    card_uid,
    active,
    updated_at
) ON TABLE public.member
FROM app_runtime, cutover_writer, legacy_writer, retention_worker;

GRANT USAGE ON SCHEMA public
    TO app_runtime, cutover_writer, legacy_writer, retention_worker;
REVOKE CREATE ON SCHEMA public
    FROM app_runtime, cutover_writer, legacy_writer, retention_worker;

-- This worker may only invoke the fixed-cutoff batch function. It has no
-- direct table, sequence, or other function privileges, and its credential is
-- never injected into the web application container.
GRANT EXECUTE ON FUNCTION public.attend_purge_expired_audit_log_batch()
TO retention_worker;
GRANT EXECUTE ON FUNCTION public.attend_purge_expired_tag_event_log_batch()
TO retention_worker;

-- New runtime and temporary cutover identities share the same application
-- boundary. cutover_writer is separate so its login can be disabled after use.
GRANT SELECT, INSERT ON TABLE
    public.department,
    public.account,
    public.account_credential_token,
    public.account_department_role,
    public.department_membership,
    public.nfc_card,
    public.nfc_card_assignment,
    public.device,
    public.attendance_policy_version,
    public.attendance_band,
    public.attendance_day,
    public.attendance_target,
    public.attendance_record,
    public.tag_event_log,
    public.audit_log
TO app_runtime, cutover_writer;

GRANT UPDATE (
    password_hash,
    status,
    password_changed_at
) ON TABLE public.account
TO app_runtime, cutover_writer;

GRANT UPDATE (
    consumed_at,
    revoked_at
) ON TABLE public.account_credential_token
TO app_runtime, cutover_writer;

GRANT UPDATE (revoked_at)
ON TABLE public.account_department_role
TO app_runtime, cutover_writer;

GRANT UPDATE (
    ended_at,
    ended_by_account_id,
    end_reason
) ON TABLE public.department_membership
TO app_runtime, cutover_writer;

GRANT UPDATE (status)
ON TABLE public.nfc_card
TO app_runtime, cutover_writer;

GRANT UPDATE (
    unassigned_by_account_id,
    unassigned_at,
    end_reason
) ON TABLE public.nfc_card_assignment
TO app_runtime, cutover_writer;

GRANT UPDATE (
    name,
    credential_hash,
    credential_version,
    status,
    credential_issued_at,
    credential_tested_version,
    credential_tested_at,
    last_seen_at
) ON TABLE public.device
TO app_runtime, cutover_writer;

GRANT UPDATE (
    name,
    check_in_start_time,
    status,
    published_by_account_id,
    published_at
) ON TABLE public.attendance_policy_version
TO app_runtime, cutover_writer;

GRANT UPDATE (
    sequence_no,
    label,
    parent_status,
    upper_time
), DELETE ON TABLE public.attendance_band
TO app_runtime, cutover_writer;

GRANT UPDATE (
    status,
    canceled_by_account_id,
    finalized_at,
    canceled_at,
    cancel_reason
) ON TABLE public.attendance_day
TO app_runtime, cutover_writer;

GRANT UPDATE (
    is_target,
    changed_by_account_id,
    changed_at,
    change_reason
) ON TABLE public.attendance_target
TO app_runtime, cutover_writer;

GRANT UPDATE (
    attendance_band_id,
    status,
    band_sequence_snapshot,
    band_label_snapshot,
    checked_in_at,
    source,
    note,
    updated_by_account_id
) ON TABLE public.attendance_record
TO app_runtime, cutover_writer;

GRANT UPDATE (
    nfc_card_id,
    attendance_day_id,
    attendance_record_id,
    result_code,
    http_status,
    response_body,
    failure_type
) ON TABLE public.tag_event_log
TO app_runtime, cutover_writer;

GRANT SELECT ON TABLE public.flyway_schema_history
TO app_runtime, cutover_writer;

GRANT SELECT (
    id,
    name,
    phone,
    birth,
    created_at,
    active,
    updated_at
), INSERT (
    name,
    phone,
    birth,
    active
), UPDATE (
    name,
    phone,
    birth,
    active
) ON TABLE public.member
TO app_runtime, cutover_writer;

GRANT USAGE ON SEQUENCE
    public.member_id_seq,
    public.department_id_seq,
    public.account_id_seq,
    public.account_credential_token_id_seq,
    public.account_department_role_id_seq,
    public.department_membership_id_seq,
    public.nfc_card_id_seq,
    public.nfc_card_assignment_id_seq,
    public.device_id_seq,
    public.attendance_policy_version_id_seq,
    public.attendance_band_id_seq,
    public.attendance_day_id_seq,
    public.attendance_record_id_seq,
    public.tag_event_log_id_seq,
    public.audit_log_id_seq
TO app_runtime, cutover_writer;

-- The safe legacy release can keep working during a controlled rollback window,
-- but cannot delete members or change card_uid. These grants are only added when
-- the exact four-table legacy schema was adopted.
GRANT SELECT (
    id,
    name,
    age,
    phone,
    birth,
    created_at,
    card_uid,
    active,
    updated_at
), INSERT (
    name,
    age,
    phone,
    birth
), UPDATE (
    name,
    age,
    phone,
    birth
) ON TABLE public.member
TO legacy_writer;

GRANT USAGE ON SEQUENCE public.member_id_seq TO legacy_writer;

DO $legacy_grants$
BEGIN
    IF pg_catalog.to_regclass('public.authentications') IS NOT NULL
       AND pg_catalog.to_regclass('public.attendance') IS NOT NULL
       AND pg_catalog.to_regclass('public.attendance_log') IS NOT NULL THEN
        GRANT SELECT ON TABLE public.authentications TO legacy_writer;
        GRANT SELECT, INSERT ON TABLE public.attendance TO legacy_writer;
        GRANT UPDATE (note) ON TABLE public.attendance TO legacy_writer;
        GRANT SELECT, INSERT ON TABLE public.attendance_log TO legacy_writer;
        GRANT USAGE ON SEQUENCE
            public.attendance_attend_id_seq,
            public.attendance_log_id_seq
        TO legacy_writer;
    END IF;
END
$legacy_grants$;

COMMIT;
