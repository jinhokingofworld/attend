-- The final attendance policy band's upper_time is inclusive. PostgreSQL stores
-- timestamps at microsecond precision, so persist the first representable instant
-- after that boundary instead of relying on a nanosecond that would be truncated.
UPDATE public.attendance_day AS day
SET finalization_due_at = (
    (
        day.attendance_date + (
            SELECT band.upper_time
            FROM public.attendance_band AS band
            WHERE band.policy_version_id = day.policy_version_id
            ORDER BY band.sequence_no DESC
            LIMIT 1
        )
    ) AT TIME ZONE 'Asia/Seoul'
) + INTERVAL '1 microsecond';
