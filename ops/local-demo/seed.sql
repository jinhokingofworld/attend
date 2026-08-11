-- Local-only data for Postman/curl device API testing.
-- The values below are synthetic and must never be copied into production.

-- CI can override this psql variable to prove that the daily upsert survives a
-- date boundary. Local execution leaves it empty and uses the current Seoul date.
SET attend.local_demo_date TO :'local_attendance_date';

DO $local_demo$
DECLARE
    actor_id BIGINT;
    department_admin_id BIGINT;
    department_a_id BIGINT;
    department_b_id BIGINT;
    member_a_id BIGINT;
    member_b_id BIGINT;
    member_inbox_id BIGINT;
    membership_a_id BIGINT;
    membership_b_id BIGINT;
    card_a_id BIGINT;
    card_b_id BIGINT;
    policy_a_id BIGINT;
    policy_b_id BIGINT;
    day_a_id BIGINT;
    day_b_id BIGINT;
    local_attendance_date DATE := COALESCE(
        NULLIF(current_setting('attend.local_demo_date'), '')::DATE,
        (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::DATE
    );
BEGIN
    -- 고정 fixture는 한 번만 만들되, 아래의 오늘 출석일은 매 실행마다 보강한다.
    -- 세 장치 중 일부만 존재하면 손상된 fixture로 간주해 조용히 중복 생성하지 않는다.
    IF (
        SELECT COUNT(*) FROM public.device
        WHERE device_code IN (
            'local-device-a', 'local-device-b', 'local-device-provisioning')
    ) NOT IN (0, 3) THEN
        RAISE EXCEPTION
            'Attend local demo fixture is incomplete; reset the local volume';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.device WHERE device_code = 'local-device-a'
    ) THEN
        -- 기존 fixture의 식별자를 다시 읽어 날짜별 데이터 생성에 사용한다.
        SELECT id INTO STRICT actor_id
        FROM public.account WHERE username = 'local-system-admin';
        SELECT id INTO STRICT department_admin_id
        FROM public.account WHERE username = 'local-department-admin';

        SELECT department_id INTO STRICT department_a_id
        FROM public.device WHERE device_code = 'local-device-a';
        SELECT department_id INTO STRICT department_b_id
        FROM public.device WHERE device_code = 'local-device-b';

        SELECT assignment.member_id, assignment.membership_id
        INTO STRICT member_a_id, membership_a_id
        FROM public.nfc_card card
        JOIN public.nfc_card_assignment assignment
          ON assignment.nfc_card_id = card.id
         AND assignment.unassigned_at IS NULL
        WHERE card.uid = '04A1B2C3';

        SELECT assignment.member_id, assignment.membership_id
        INTO STRICT member_b_id, membership_b_id
        FROM public.nfc_card card
        JOIN public.nfc_card_assignment assignment
          ON assignment.nfc_card_id = card.id
         AND assignment.unassigned_at IS NULL
        WHERE card.uid = '04D4E5F6';

        SELECT id INTO STRICT policy_a_id
        FROM public.attendance_policy_version
        WHERE department_id = department_a_id AND version_no = 1;
        SELECT id INTO STRICT policy_b_id
        FROM public.attendance_policy_version
        WHERE department_id = department_b_id AND version_no = 1;

        RAISE NOTICE 'Attend local base fixture already exists';
    ELSE

    INSERT INTO public.account(
        username, password_hash, system_role, status, password_changed_at)
    VALUES (
        'local-system-admin',
        '$2y$12$IfoObjHJ0tL/BIJMH6SHV.Z8mRXdYw09Au2ILUK.aGoZsZ4Aj3T/O',
        'SYSTEM_ADMIN',
        'ACTIVE', CURRENT_TIMESTAMP)
    RETURNING id INTO actor_id;

    INSERT INTO public.account(
        username, password_hash, system_role, status, password_changed_at)
    VALUES (
        'local-department-admin',
        '$2y$12$rPNeEIhyvPMaQnDCCMo8GOdq1rACn7uGj.UQXFf5Xgyx.IkqGxr.S',
        NULL, 'ACTIVE', CURRENT_TIMESTAMP)
    RETURNING id INTO department_admin_id;

    INSERT INTO public.department(name)
    VALUES ('로컬 데모 A부서')
    RETURNING id INTO department_a_id;
    INSERT INTO public.department(name)
    VALUES ('로컬 데모 B부서')
    RETURNING id INTO department_b_id;

    INSERT INTO public.account_department_role(
        account_id, department_id, role, assigned_by_account_id)
    VALUES
        (department_admin_id, department_a_id, 'DEPARTMENT_ADMIN', actor_id),
        (department_admin_id, department_b_id, 'DEPARTMENT_ADMIN', actor_id);

    INSERT INTO public.member(name, birth, active)
    VALUES ('로컬 교사 A', DATE '1990-03-15', TRUE)
    RETURNING id INTO member_a_id;
    INSERT INTO public.member(name, birth, active)
    VALUES ('로컬 교사 B', DATE '1988-10-21', TRUE)
    RETURNING id INTO member_b_id;

    INSERT INTO public.department_membership(
        department_id, member_id, joined_at, created_by_account_id)
    VALUES (
        department_a_id, member_a_id, CURRENT_TIMESTAMP - INTERVAL '1 day',
        actor_id)
    RETURNING id INTO membership_a_id;
    INSERT INTO public.department_membership(
        department_id, member_id, joined_at, created_by_account_id)
    VALUES (
        department_b_id, member_b_id, CURRENT_TIMESTAMP - INTERVAL '1 day',
        actor_id)
    RETURNING id INTO membership_b_id;

    INSERT INTO public.nfc_card(uid, status)
    VALUES ('04A1B2C3', 'ACTIVE')
    RETURNING id INTO card_a_id;
    INSERT INTO public.nfc_card(uid, status)
    VALUES ('04D4E5F6', 'ACTIVE')
    RETURNING id INTO card_b_id;

    INSERT INTO public.nfc_card_assignment(
        nfc_card_id, department_id, membership_id, member_id,
        assigned_by_account_id)
    VALUES (
        card_a_id, department_a_id, membership_a_id, member_a_id, actor_id);
    INSERT INTO public.nfc_card_assignment(
        nfc_card_id, department_id, membership_id, member_id,
        assigned_by_account_id)
    VALUES (
        card_b_id, department_b_id, membership_b_id, member_b_id, actor_id);

    INSERT INTO public.attendance_policy_version(
        department_id, version_no, name, check_in_start_time, status,
        created_by_account_id, published_by_account_id, published_at)
    VALUES (
        department_a_id, 1, '로컬 종일 정책', '00:00', 'PUBLISHED',
        actor_id, actor_id, CURRENT_TIMESTAMP)
    RETURNING id INTO policy_a_id;
    INSERT INTO public.attendance_policy_version(
        department_id, version_no, name, check_in_start_time, status,
        created_by_account_id, published_by_account_id, published_at)
    VALUES (
        department_b_id, 1, '로컬 종일 정책', '00:00', 'PUBLISHED',
        actor_id, actor_id, CURRENT_TIMESTAMP)
    RETURNING id INTO policy_b_id;

    INSERT INTO public.attendance_band(
        policy_version_id, sequence_no, label, parent_status, upper_time)
    VALUES
        (policy_a_id, 1, '정상 출석', 'PRESENT', '23:59:00'),
        (policy_a_id, 2, '로컬 마감 지각', 'LATE', '23:59:59.999999'),
        (policy_b_id, 1, '정상 출석', 'PRESENT', '23:59:00'),
        (policy_b_id, 2, '로컬 마감 지각', 'LATE', '23:59:59.999999');

    -- credential_hash는 아래 로컬 key와 로컬 pepper의 HMAC-SHA-256이다.
    INSERT INTO public.device(
        department_id, device_code, name, credential_hash,
        credential_version, status, credential_issued_at,
        credential_tested_version, credential_tested_at)
    VALUES
        (
            department_a_id, 'local-device-a', '로컬 장치 A',
            '36f4f98fc2ab198d3e5b65c8245e4ea83d74aabf2767149b1a30b4bc46100be6',
            1, 'ACTIVE', CURRENT_TIMESTAMP - INTERVAL '1 minute',
            1, CURRENT_TIMESTAMP
        ),
        (
            department_b_id, 'local-device-b', '로컬 장치 B',
            '6f20a2620128a540a1e47015c390a01bf56bd30b9cef06e6f27d430e7faaacf0',
            1, 'ACTIVE', CURRENT_TIMESTAMP - INTERVAL '1 minute',
            1, CURRENT_TIMESTAMP
        ),
        (
            department_a_id, 'local-device-provisioning', '로컬 등록 시험 장치',
            'a0d4fc8b20c70f67e02aa29e0982f26597a8965646eec87d4e9dc494914e8007',
            1, 'INACTIVE', CURRENT_TIMESTAMP, NULL, NULL
        );
    END IF;

    -- 카드 등록함 연결 시험용으로 카드가 없는 활성 교사를 한 명 유지한다.
    SELECT member.id
    INTO member_inbox_id
    FROM public.department_membership AS membership
    JOIN public.member AS member ON member.id = membership.member_id
    WHERE membership.department_id = department_a_id
      AND membership.ended_at IS NULL
      AND member.name = '로컬 카드 대기 교사'
    ORDER BY membership.id
    LIMIT 1;

    IF member_inbox_id IS NULL THEN
        INSERT INTO public.member(name, birth, active)
        VALUES ('로컬 카드 대기 교사', DATE '1995-06-20', TRUE)
        RETURNING id INTO member_inbox_id;
        INSERT INTO public.department_membership(
            department_id, member_id, joined_at, created_by_account_id)
        VALUES (
            department_a_id, member_inbox_id,
            CURRENT_TIMESTAMP - INTERVAL '1 day', actor_id);
    END IF;

    -- 카드 등록함에서 원본 UID 노출 없는 연결 흐름을 시험할 합성 이벤트다.
    INSERT INTO public.tag_event_log(
        device_id, department_id, request_id, uid, result_code,
        http_status, response_body, failure_type, received_at)
    SELECT device.id,
           device.department_id,
           'local-inbox-card',
           '04ABCDEF',
           'UNKNOWN_UID',
           404,
           '{"code":"UNKNOWN_UID"}'::JSONB,
           'UNKNOWN_UID',
           CURRENT_TIMESTAMP
    FROM public.device AS device
    WHERE device.device_code = 'local-device-a'
    ON CONFLICT (device_id, request_id) DO NOTHING;

    -- 날짜가 바뀌어도 오늘의 출석일과 대상자는 항상 존재하도록 멱등 upsert한다.
    INSERT INTO public.attendance_day(
        department_id, attendance_date, policy_version_id, status,
        created_by_account_id, finalization_due_at)
    VALUES (
        department_a_id, local_attendance_date, policy_a_id, 'SCHEDULED',
        actor_id,
        ((
            local_attendance_date + (
                SELECT band.upper_time
                FROM public.attendance_band AS band
                WHERE band.policy_version_id = policy_a_id
                ORDER BY band.sequence_no DESC
                LIMIT 1
            )
        ) AT TIME ZONE 'Asia/Seoul') + INTERVAL '1 microsecond')
    ON CONFLICT (department_id, attendance_date) DO UPDATE
    SET finalization_due_at = EXCLUDED.finalization_due_at;
    SELECT id INTO STRICT day_a_id
    FROM public.attendance_day
    WHERE department_id = department_a_id
      AND attendance_date = local_attendance_date;

    INSERT INTO public.attendance_day(
        department_id, attendance_date, policy_version_id, status,
        created_by_account_id, finalization_due_at)
    VALUES (
        department_b_id, local_attendance_date, policy_b_id, 'SCHEDULED',
        actor_id,
        ((
            local_attendance_date + (
                SELECT band.upper_time
                FROM public.attendance_band AS band
                WHERE band.policy_version_id = policy_b_id
                ORDER BY band.sequence_no DESC
                LIMIT 1
            )
        ) AT TIME ZONE 'Asia/Seoul') + INTERVAL '1 microsecond')
    ON CONFLICT (department_id, attendance_date) DO UPDATE
    SET finalization_due_at = EXCLUDED.finalization_due_at;
    SELECT id INTO STRICT day_b_id
    FROM public.attendance_day
    WHERE department_id = department_b_id
      AND attendance_date = local_attendance_date;

    INSERT INTO public.attendance_target(
        attendance_day_id, member_id, department_id, membership_id)
    VALUES
        (day_a_id, member_a_id, department_a_id, membership_a_id),
        (day_b_id, member_b_id, department_b_id, membership_b_id)
    ON CONFLICT (attendance_day_id, member_id) DO NOTHING;
END
$local_demo$;

SELECT 'local_demo_seed=READY' AS result;
