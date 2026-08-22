-- V020 skipped every grant when an optional cutover_writer role was absent.
-- The web runtime must receive its policy-schedule privileges independently.
DO $repair_policy_schedule_runtime_privileges$
DECLARE
    role_name TEXT;
BEGIN
    FOR role_name IN
        SELECT rolname
        FROM pg_catalog.pg_roles
        WHERE rolname IN ('app_runtime', 'cutover_writer')
    LOOP
        EXECUTE format('GRANT SELECT, INSERT ON TABLE
            public.attendance_policy_schedule,
            public.attendance_policy_schedule_weekday,
            public.attendance_policy_schedule_monthday
            TO %I', role_name);

        EXECUTE format('GRANT DELETE ON TABLE
            public.attendance_policy_schedule_weekday,
            public.attendance_policy_schedule_monthday
            TO %I', role_name);

        EXECUTE format('GRANT UPDATE (
            policy_version_id, status, start_date, end_date, recurrence,
            interval_value, yearly_month, yearly_day, updated_by_account_id,
            updated_at, archived_at
        ) ON TABLE public.attendance_policy_schedule TO %I', role_name);

        EXECUTE format('GRANT UPDATE (policy_schedule_id)
            ON TABLE public.attendance_day TO %I', role_name);

        EXECUTE format('GRANT USAGE ON SEQUENCE public.attendance_policy_schedule_id_seq
            TO %I', role_name);
    END LOOP;
END
$repair_policy_schedule_runtime_privileges$;
