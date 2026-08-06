-- Tag event retention uses the same isolated worker boundary as audit retention.
-- The cutoff is fixed in PostgreSQL so callers cannot widen the deletion range.

CREATE INDEX idx_tag_event_received_id
    ON public.tag_event_log (received_at, id);

-- `received_at` is the server-side ingest time, not a device-provided or web
-- runtime-controlled retention escape hatch. The attendance decision still
-- uses its request-time value separately; this log timestamp is authoritative
-- for the 90-day lifecycle.
CREATE FUNCTION public.attend_set_tag_event_received_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.received_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.attend_set_tag_event_received_at() FROM PUBLIC;

CREATE TRIGGER trg_tag_event_received_at
BEFORE INSERT ON public.tag_event_log
FOR EACH ROW
EXECUTE FUNCTION public.attend_set_tag_event_received_at();

-- One call deletes at most 500 tag events older than 90 days. SKIP LOCKED
-- keeps independently scheduled workers from selecting the same rows.
CREATE FUNCTION public.attend_purge_expired_tag_event_log_batch()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    deleted_rows integer;
BEGIN
    WITH expired_ids AS (
        SELECT event.id
        FROM public.tag_event_log AS event
        WHERE event.received_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
        ORDER BY event.received_at ASC, event.id ASC
        LIMIT 500
        FOR UPDATE SKIP LOCKED
    ), deleted AS (
        DELETE FROM public.tag_event_log AS event
        USING expired_ids
        WHERE event.id = expired_ids.id
        RETURNING 1
    )
    SELECT count(*) INTO deleted_rows
    FROM deleted;

    RETURN deleted_rows;
END;
$$;

REVOKE ALL ON FUNCTION public.attend_purge_expired_tag_event_log_batch() FROM PUBLIC;
