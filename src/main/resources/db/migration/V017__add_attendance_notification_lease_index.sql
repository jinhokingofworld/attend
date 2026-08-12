-- Processing rows are included in the dynamic delivery wake-up calculation.
-- Keep lease expiry discovery index-backed as the outbox grows.
-- The DROP makes a repaired rerun recover from an invalid index left by an
-- interrupted CREATE INDEX CONCURRENTLY.
DROP INDEX CONCURRENTLY IF EXISTS public.idx_notification_outbox_lease;

CREATE INDEX CONCURRENTLY idx_notification_outbox_lease
    ON public.attendance_notification_outbox(lease_until, id)
    WHERE status = 'PROCESSING';
