-- AttendanceDayMapper.insertDayIfAbsent uses INSERT ... ON CONFLICT DO UPDATE
-- to restore a canceled date owned by the same policy schedule. PostgreSQL
-- checks every UPDATE target in that statement even when the insert path wins,
-- so policy_version_id needs an explicit column grant for the runtime role.
DO $grant_attendance_day_policy_version_update$
DECLARE
    role_name TEXT;
BEGIN
    FOR role_name IN
        SELECT rolname
        FROM pg_catalog.pg_roles
        WHERE rolname IN ('app_runtime', 'cutover_writer')
    LOOP
        EXECUTE format('GRANT UPDATE (policy_version_id)
            ON TABLE public.attendance_day TO %I', role_name);
    END LOOP;
END
$grant_attendance_day_policy_version_update$;
