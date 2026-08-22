-- Keep V019's policy schedule schema and the runtime account privileges in the
-- same release. Roles are provisioned outside Flyway, so a fresh schema used by
-- migration-only tests may not have them yet; production role setup creates both
-- roles before this migration is run.
DO $grant_policy_schedule_runtime_privileges$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'app_runtime')
       OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cutover_writer') THEN
        RETURN;
    END IF;

    EXECUTE 'GRANT SELECT, INSERT ON TABLE
        public.attendance_policy_schedule,
        public.attendance_policy_schedule_weekday,
        public.attendance_policy_schedule_monthday
        TO app_runtime, cutover_writer';

    EXECUTE 'GRANT DELETE ON TABLE
        public.attendance_policy_schedule_weekday,
        public.attendance_policy_schedule_monthday
        TO app_runtime, cutover_writer';

    EXECUTE 'GRANT UPDATE (
        policy_version_id,
        status,
        start_date,
        end_date,
        recurrence,
        interval_value,
        yearly_month,
        yearly_day,
        updated_by_account_id,
        updated_at,
        archived_at
    ) ON TABLE public.attendance_policy_schedule
    TO app_runtime, cutover_writer';

    EXECUTE 'GRANT UPDATE (policy_schedule_id)
        ON TABLE public.attendance_day
        TO app_runtime, cutover_writer';

    EXECUTE 'GRANT USAGE ON SEQUENCE public.attendance_policy_schedule_id_seq
        TO app_runtime, cutover_writer';
END
$grant_policy_schedule_runtime_privileges$;
