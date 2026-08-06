-- Audit retention is intentionally isolated from the web runtime role. The
-- only deletion path is the fixed, no-argument SECURITY DEFINER function
-- below; its cutoff is calculated by PostgreSQL, never supplied by a caller.

-- Existing audit indexes start with department, target, or actor. A global
-- time-ordered index keeps retention batches bounded as the table grows.
CREATE INDEX idx_audit_occurred_id
    ON public.audit_log (occurred_at, id);

-- Runtime callers must not be able to forge a historical or future audit time
-- through an explicit INSERT column. Business mappers already omit
-- occurred_at, so this makes the retention clock an authoritative DB value.
CREATE FUNCTION public.attend_set_audit_occurred_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.occurred_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.attend_set_audit_occurred_at() FROM PUBLIC;

CREATE TRIGGER trg_audit_occurred_at
BEFORE INSERT ON public.audit_log
FOR EACH ROW
EXECUTE FUNCTION public.attend_set_audit_occurred_at();

-- One call deletes at most 500 rows and commits with the caller's statement.
-- SKIP LOCKED makes independently scheduled workers safe: each invocation
-- owns a distinct batch, while a failure rolls back only its own batch.
CREATE FUNCTION public.attend_purge_expired_audit_log_batch()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    deleted_rows integer;
BEGIN
    WITH expired_ids AS (
        SELECT audit.id
        FROM public.audit_log AS audit
        WHERE audit.occurred_at < CURRENT_TIMESTAMP - INTERVAL '2 years'
        ORDER BY audit.occurred_at ASC, audit.id ASC
        LIMIT 500
        FOR UPDATE SKIP LOCKED
    ), deleted AS (
        DELETE FROM public.audit_log AS audit
        USING expired_ids
        WHERE audit.id = expired_ids.id
        RETURNING 1
    )
    SELECT count(*) INTO deleted_rows
    FROM deleted;

    RETURN deleted_rows;
END;
$$;

REVOKE ALL ON FUNCTION public.attend_purge_expired_audit_log_batch() FROM PUBLIC;
