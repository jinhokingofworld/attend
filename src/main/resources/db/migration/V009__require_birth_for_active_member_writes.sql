-- Preserve unverified legacy rows while requiring an exact, non-future birth
-- date for every new teacher, basic-information change, or activation.
--
-- The column deliberately stays nullable: adopting a legacy database must not
-- invent dates, and ending the membership of an old row must remain possible
-- before its birth date is known.
CREATE FUNCTION public.attend_require_member_birth_on_write()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    business_date DATE :=
        (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::DATE;
    requires_verified_birth BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        requires_verified_birth := TRUE;
    ELSE
        requires_verified_birth :=
            NEW.active IS TRUE
            OR NEW.name IS DISTINCT FROM OLD.name
            OR NEW.age IS DISTINCT FROM OLD.age
            OR NEW.phone IS DISTINCT FROM OLD.phone
            OR NEW.birth IS DISTINCT FROM OLD.birth;
    END IF;

    IF requires_verified_birth AND NEW.birth IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'member birth date is required for this write',
            CONSTRAINT = 'ck_member_birth_required_on_write';
    END IF;

    IF requires_verified_birth AND NEW.birth > business_date THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'member birth date cannot be in the future',
            CONSTRAINT = 'ck_member_birth_not_future_on_write';
    END IF;

    -- Closing the last membership must happen first. This keeps direct SQL from
    -- making an operational membership point at an inactive member.
    IF TG_OP = 'UPDATE' THEN
        IF OLD.active IS TRUE
           AND NEW.active IS FALSE
           AND EXISTS (
               SELECT 1
               FROM public.department_membership AS membership
               WHERE membership.member_id = NEW.id
                 AND membership.ended_at IS NULL
           ) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'member with an active membership cannot be deactivated',
                CONSTRAINT = 'ck_member_active_membership_on_write';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

-- Trigger execution does not require callers to invoke the function directly.
REVOKE ALL ON FUNCTION public.attend_require_member_birth_on_write() FROM PUBLIC;

CREATE TRIGGER trg_member_birth_required_on_insert
BEFORE INSERT ON public.member
FOR EACH ROW
EXECUTE FUNCTION public.attend_require_member_birth_on_write();

CREATE TRIGGER trg_member_birth_required_on_operational_update
BEFORE UPDATE OF name, age, phone, birth, active ON public.member
FOR EACH ROW
EXECUTE FUNCTION public.attend_require_member_birth_on_write();


-- Lock the member row while opening a membership so membership creation cannot
-- race with member deactivation. Existing inconsistent legacy rows are not
-- scanned or rewritten by this migration.
CREATE FUNCTION public.attend_require_operational_membership_member()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    member_active BOOLEAN;
    member_birth DATE;
    business_date DATE :=
        (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::DATE;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'closed membership history is immutable',
            CONSTRAINT = 'ck_closed_membership_immutable_on_write';
    END IF;

    IF NEW.ended_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    SELECT member.active, member.birth
      INTO member_active, member_birth
      FROM public.member AS member
     WHERE member.id = NEW.member_id
     FOR UPDATE;

    -- Let the foreign key report a missing member with its canonical error.
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF member_active IS NOT TRUE
       OR member_birth IS NULL
       OR member_birth > business_date THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'active membership requires an active member with a verified birth date',
            CONSTRAINT = 'ck_membership_operational_member_on_write';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION
    public.attend_require_operational_membership_member()
FROM PUBLIC;

CREATE TRIGGER trg_membership_member_required_on_insert
BEFORE INSERT ON public.department_membership
FOR EACH ROW
EXECUTE FUNCTION public.attend_require_operational_membership_member();

CREATE TRIGGER trg_membership_write_guard_on_update
BEFORE UPDATE ON public.department_membership
FOR EACH ROW
EXECUTE FUNCTION public.attend_require_operational_membership_member();


-- A new assignment row represents every reconnection. Once an assignment has
-- been closed, its actor, timestamp, reason, and identity are historical facts.
CREATE FUNCTION public.attend_require_closed_card_assignment_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.unassigned_at IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'closed card assignment history is immutable',
            CONSTRAINT = 'ck_closed_card_assignment_immutable_on_write';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION
    public.attend_require_closed_card_assignment_immutable()
FROM PUBLIC;

CREATE TRIGGER trg_card_assignment_closed_history_immutable
BEFORE UPDATE ON public.nfc_card_assignment
FOR EACH ROW
EXECUTE FUNCTION public.attend_require_closed_card_assignment_immutable();
