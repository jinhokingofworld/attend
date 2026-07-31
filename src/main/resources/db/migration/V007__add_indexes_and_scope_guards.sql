ALTER TABLE public.nfc_card_assignment
    ADD CONSTRAINT fk_card_assignment_membership
        FOREIGN KEY (membership_id, department_id, member_id)
        REFERENCES public.department_membership (id, department_id, member_id)
        ON DELETE RESTRICT;

ALTER TABLE public.attendance_day
    ADD CONSTRAINT fk_attendance_day_policy
        FOREIGN KEY (policy_version_id, department_id)
        REFERENCES public.attendance_policy_version (id, department_id)
        ON DELETE RESTRICT;

ALTER TABLE public.attendance_target
    ADD CONSTRAINT fk_target_day
        FOREIGN KEY (attendance_day_id, department_id)
        REFERENCES public.attendance_day (id, department_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_target_membership
        FOREIGN KEY (membership_id, department_id, member_id)
        REFERENCES public.department_membership (id, department_id, member_id)
        ON DELETE RESTRICT;

ALTER TABLE public.tag_event_log
    ADD CONSTRAINT fk_tag_event_device
        FOREIGN KEY (device_id, department_id)
        REFERENCES public.device (id, department_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tag_event_day
        FOREIGN KEY (attendance_day_id, department_id)
        REFERENCES public.attendance_day (id, department_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tag_event_record
        FOREIGN KEY (attendance_record_id, attendance_day_id)
        REFERENCES public.attendance_record (id, attendance_day_id)
        ON DELETE RESTRICT;

ALTER TABLE public.audit_log
    ADD CONSTRAINT fk_audit_actor_device_scope
        FOREIGN KEY (actor_device_id, department_id)
        REFERENCES public.device (id, department_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_audit_day
        FOREIGN KEY (attendance_day_id, department_id)
        REFERENCES public.attendance_day (id, department_id)
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_account_credential_token_active
    ON public.account_credential_token (account_id, purpose)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX idx_account_credential_token_account
    ON public.account_credential_token (account_id);

CREATE INDEX idx_account_credential_token_issuer
    ON public.account_credential_token (issued_by_account_id);

CREATE UNIQUE INDEX uq_dept_role_active
    ON public.account_department_role (account_id, department_id, role)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_dept_role_department_account
    ON public.account_department_role (department_id, account_id);

CREATE INDEX idx_dept_role_account
    ON public.account_department_role (account_id);

CREATE INDEX idx_dept_role_assigned_by
    ON public.account_department_role (assigned_by_account_id)
    WHERE assigned_by_account_id IS NOT NULL;

CREATE UNIQUE INDEX uq_membership_one_active_per_member
    ON public.department_membership (member_id)
    WHERE ended_at IS NULL;

CREATE UNIQUE INDEX uq_membership_active_in_department
    ON public.department_membership (department_id, member_id)
    WHERE ended_at IS NULL;

CREATE INDEX idx_membership_department_member
    ON public.department_membership (department_id, member_id);

CREATE INDEX idx_membership_member_joined
    ON public.department_membership (member_id, joined_at DESC);

CREATE INDEX idx_membership_created_by
    ON public.department_membership (created_by_account_id)
    WHERE created_by_account_id IS NOT NULL;

CREATE INDEX idx_membership_ended_by
    ON public.department_membership (ended_by_account_id)
    WHERE ended_by_account_id IS NOT NULL;

CREATE UNIQUE INDEX uq_card_one_active_assignment
    ON public.nfc_card_assignment (nfc_card_id)
    WHERE unassigned_at IS NULL;

CREATE UNIQUE INDEX uq_member_one_active_card
    ON public.nfc_card_assignment (member_id)
    WHERE unassigned_at IS NULL;

CREATE INDEX idx_card_assignment_department_member
    ON public.nfc_card_assignment (department_id, member_id);

CREATE INDEX idx_card_assignment_card
    ON public.nfc_card_assignment (nfc_card_id);

CREATE INDEX idx_card_assignment_member
    ON public.nfc_card_assignment (member_id);

CREATE INDEX idx_card_assignment_membership
    ON public.nfc_card_assignment (membership_id);

CREATE INDEX idx_card_assignment_assigned_by
    ON public.nfc_card_assignment (assigned_by_account_id)
    WHERE assigned_by_account_id IS NOT NULL;

CREATE INDEX idx_card_assignment_unassigned_by
    ON public.nfc_card_assignment (unassigned_by_account_id)
    WHERE unassigned_by_account_id IS NOT NULL;

CREATE INDEX idx_device_department_status
    ON public.device (department_id, status);

CREATE INDEX idx_policy_department_status_version
    ON public.attendance_policy_version
        (department_id, status, version_no DESC);

CREATE INDEX idx_policy_created_by
    ON public.attendance_policy_version (created_by_account_id);

CREATE INDEX idx_policy_published_by
    ON public.attendance_policy_version (published_by_account_id)
    WHERE published_by_account_id IS NOT NULL;

CREATE INDEX idx_attendance_day_unfinalized
    ON public.attendance_day (attendance_date, id)
    WHERE status = 'SCHEDULED';

CREATE INDEX idx_attendance_day_policy_date
    ON public.attendance_day (policy_version_id, attendance_date DESC);

CREATE INDEX idx_attendance_day_created_by
    ON public.attendance_day (created_by_account_id);

CREATE INDEX idx_attendance_day_canceled_by
    ON public.attendance_day (canceled_by_account_id)
    WHERE canceled_by_account_id IS NOT NULL;

CREATE INDEX idx_target_department_day
    ON public.attendance_target (department_id, attendance_day_id);

CREATE INDEX idx_target_membership
    ON public.attendance_target (membership_id);

CREATE INDEX idx_target_member
    ON public.attendance_target (member_id);

CREATE INDEX idx_target_member_day_active
    ON public.attendance_target (member_id, attendance_day_id)
    WHERE is_target;

CREATE INDEX idx_target_changed_by
    ON public.attendance_target (changed_by_account_id)
    WHERE changed_by_account_id IS NOT NULL;

CREATE INDEX idx_record_member_day_status
    ON public.attendance_record (member_id, attendance_day_id, status);

CREATE INDEX idx_record_band
    ON public.attendance_record (attendance_band_id)
    WHERE attendance_band_id IS NOT NULL;

CREATE INDEX idx_record_created_by
    ON public.attendance_record (created_by_account_id)
    WHERE created_by_account_id IS NOT NULL;

CREATE INDEX idx_record_updated_by
    ON public.attendance_record (updated_by_account_id)
    WHERE updated_by_account_id IS NOT NULL;

CREATE INDEX idx_tag_event_department_received
    ON public.tag_event_log (department_id, received_at DESC);

CREATE INDEX idx_tag_event_unknown_uid
    ON public.tag_event_log (department_id, received_at DESC)
    WHERE result_code = 'UNKNOWN_UID';

CREATE INDEX idx_tag_event_card_received
    ON public.tag_event_log (nfc_card_id, received_at DESC)
    WHERE nfc_card_id IS NOT NULL;

CREATE INDEX idx_tag_event_day_received
    ON public.tag_event_log (attendance_day_id, received_at DESC)
    WHERE attendance_day_id IS NOT NULL;

CREATE INDEX idx_tag_event_record
    ON public.tag_event_log (attendance_record_id)
    WHERE attendance_record_id IS NOT NULL;

CREATE INDEX idx_audit_department_occurred
    ON public.audit_log (department_id, occurred_at DESC)
    WHERE department_id IS NOT NULL;

CREATE INDEX idx_audit_target_occurred
    ON public.audit_log (target_type, target_id, occurred_at DESC);

CREATE INDEX idx_audit_actor_account
    ON public.audit_log (actor_account_id, occurred_at DESC)
    WHERE actor_account_id IS NOT NULL;

CREATE INDEX idx_audit_actor_device
    ON public.audit_log (actor_device_id, occurred_at DESC)
    WHERE actor_device_id IS NOT NULL;

CREATE INDEX idx_audit_day
    ON public.audit_log (attendance_day_id, occurred_at DESC)
    WHERE attendance_day_id IS NOT NULL;
