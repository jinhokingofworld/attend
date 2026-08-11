-- Persist finalization retry and lease state so restarts and multiple runtime
-- instances share the same five-retry budget and cannot overwrite a newer claim.
ALTER TABLE public.attendance_day
    ADD COLUMN finalization_failure_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN finalization_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN finalization_claim_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN finalization_lease_until TIMESTAMPTZ,
    ADD COLUMN finalization_last_error_code VARCHAR(80),
    ADD COLUMN finalization_last_failed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_attendance_day_finalization_failure_count
        CHECK (finalization_failure_count BETWEEN 0 AND 6) NOT VALID,
    ADD CONSTRAINT ck_attendance_day_finalization_claim_version
        CHECK (finalization_claim_version >= 0) NOT VALID,
    ADD CONSTRAINT ck_attendance_day_finalization_retry_state
        CHECK (
            status <> 'SCHEDULED'
            OR (
                (finalization_failure_count = 0
                    AND finalization_next_attempt_at IS NULL)
                OR
                (finalization_failure_count BETWEEN 1 AND 5
                    AND finalization_next_attempt_at IS NOT NULL)
                OR
                (finalization_failure_count = 6
                    AND finalization_next_attempt_at IS NULL)
            )
        ) NOT VALID;

ALTER TABLE public.attendance_day
    VALIDATE CONSTRAINT ck_attendance_day_finalization_failure_count;

ALTER TABLE public.attendance_day
    VALIDATE CONSTRAINT ck_attendance_day_finalization_claim_version;

ALTER TABLE public.attendance_day
    VALIDATE CONSTRAINT ck_attendance_day_finalization_retry_state;

DROP INDEX CONCURRENTLY IF EXISTS
    public.idx_attendance_day_finalization_dispatch;

CREATE INDEX CONCURRENTLY idx_attendance_day_finalization_dispatch
    ON public.attendance_day (
        (COALESCE(finalization_next_attempt_at, finalization_due_at)), id)
    WHERE status = 'SCHEDULED' AND finalization_failure_count < 6;
