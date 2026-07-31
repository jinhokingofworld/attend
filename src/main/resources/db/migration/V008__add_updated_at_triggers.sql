-- Centralize updated_at maintenance so every SQL caller follows the same rule.
-- CURRENT_TIMESTAMP is the transaction start time (not a wall-clock reading for
-- each row), and even a no-op UPDATE refreshes the column.
CREATE FUNCTION public.attend_set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

-- Triggers may execute the function, but ordinary PUBLIC roles cannot call it
-- directly. V001 also rejects a pre-existing function with this identity so this
-- migration remains the known owner of the helper.
REVOKE ALL ON FUNCTION public.attend_set_updated_at() FROM PUBLIC;

CREATE TRIGGER trg_department_updated_at
BEFORE UPDATE ON public.department
FOR EACH ROW
EXECUTE FUNCTION public.attend_set_updated_at();

CREATE TRIGGER trg_account_updated_at
BEFORE UPDATE ON public.account
FOR EACH ROW
EXECUTE FUNCTION public.attend_set_updated_at();

CREATE TRIGGER trg_member_updated_at
BEFORE UPDATE ON public.member
FOR EACH ROW
EXECUTE FUNCTION public.attend_set_updated_at();

CREATE TRIGGER trg_nfc_card_updated_at
BEFORE UPDATE ON public.nfc_card
FOR EACH ROW
EXECUTE FUNCTION public.attend_set_updated_at();

CREATE TRIGGER trg_device_updated_at
BEFORE UPDATE ON public.device
FOR EACH ROW
EXECUTE FUNCTION public.attend_set_updated_at();

CREATE TRIGGER trg_attendance_record_updated_at
BEFORE UPDATE ON public.attendance_record
FOR EACH ROW
EXECUTE FUNCTION public.attend_set_updated_at();
