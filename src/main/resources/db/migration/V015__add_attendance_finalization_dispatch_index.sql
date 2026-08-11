-- Keep the online index operation isolated from transactional retry-state DDL.
-- The DROP makes a repaired rerun recover from an invalid index left by an
-- interrupted CREATE INDEX CONCURRENTLY.
DROP INDEX CONCURRENTLY IF EXISTS
    public.idx_attendance_day_finalization_dispatch;

CREATE INDEX CONCURRENTLY idx_attendance_day_finalization_dispatch
    ON public.attendance_day (
        (COALESCE(finalization_next_attempt_at, finalization_due_at)), id)
    WHERE status = 'SCHEDULED' AND finalization_failure_count < 6;
