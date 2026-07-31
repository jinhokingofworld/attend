-- V001 has exactly two accepted inputs:
--   1. a fresh public schema (apart from Flyway's own history table), or
--   2. the four-table legacy schema created by the former schema.sql.
-- Any partial or structurally different schema is rejected before alteration.
-- The BEGIN keywords below delimit PL/pgSQL bodies; this file intentionally
-- contains no top-level transaction control because Flyway owns that boundary.
--
-- DatabasePreflightInspector intentionally parses the first DO $v001$ block,
-- its $v001$; end marker, and the exact ACCESS EXCLUSIVE lock literal below.
-- Changing those boundaries must also update the Java preflight inspector.

DO $v001$
DECLARE
    legacy_table_count INTEGER;
    mismatch_count INTEGER;
    unexpected_objects TEXT;
    member_oid OID;
    authentications_oid OID;
    attendance_oid OID;
    attendance_log_oid OID;
    role_oid OID;
    attend_status_oid OID;
    attendance_fk_name NAME;
    attendance_log_fk_name NAME;
    attendance_fk_delete_action "char";
    attendance_log_fk_delete_action "char";
BEGIN
    IF pg_catalog.current_schema() IS DISTINCT FROM 'public' THEN
        RAISE EXCEPTION
            'V001 requires current_schema() = public, but found %',
            pg_catalog.current_schema();
    END IF;

    -- Catalog deparser output must not depend on a role/database-level GUC.
    PERFORM pg_catalog.set_config('quote_all_identifiers', 'off', TRUE);

    IF pg_catalog.to_regprocedure('public.attend_set_updated_at()') IS NOT NULL THEN
        RAISE EXCEPTION
            'V001 rejected schema: public.attend_set_updated_at() already exists';
    END IF;

    SELECT count(*)
      INTO legacy_table_count
      FROM pg_catalog.pg_class AS relation
      JOIN pg_catalog.pg_namespace AS namespace
        ON namespace.oid = relation.relnamespace
     WHERE namespace.nspname = 'public'
       AND relation.relname = ANY (
            ARRAY['member', 'authentications', 'attendance', 'attendance_log']
       )
       AND relation.relkind IN ('r', 'p');

    IF legacy_table_count = 0 THEN
        SELECT pg_catalog.string_agg(
                   pg_catalog.format('%I (%s)', relation.relname, relation.relkind),
                   ', '
                   ORDER BY relation.relname
               )
          INTO unexpected_objects
          FROM pg_catalog.pg_class AS relation
          JOIN pg_catalog.pg_namespace AS namespace
            ON namespace.oid = relation.relnamespace
         WHERE namespace.nspname = 'public'
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'f', 'S', 'c')
           AND NOT (
               relation.relname = 'flyway_schema_history'
               AND relation.relkind IN ('r', 'p')
           );

        IF unexpected_objects IS NOT NULL THEN
            RAISE EXCEPTION
                'V001 rejected fresh schema because unexpected relations exist: %',
                unexpected_objects;
        END IF;

        SELECT pg_catalog.string_agg(
                   pg_catalog.format('%I (%s)', data_type.typname, data_type.typtype),
                   ', '
                   ORDER BY data_type.typname
               )
          INTO unexpected_objects
          FROM pg_catalog.pg_type AS data_type
          JOIN pg_catalog.pg_namespace AS namespace
            ON namespace.oid = data_type.typnamespace
         WHERE namespace.nspname = 'public'
           AND (
               data_type.typtype IN ('d', 'e', 'm', 'r')
               OR (data_type.typtype = 'b' AND data_type.typelem = 0)
           );

        IF unexpected_objects IS NOT NULL THEN
            RAISE EXCEPTION
                'V001 rejected fresh schema because unexpected user-defined types exist: %',
                unexpected_objects;
        END IF;

        EXECUTE $create_member$
            CREATE TABLE public.member (
                id BIGSERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                age INTEGER,
                phone VARCHAR(255),
                birth DATE,
                created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                card_uid VARCHAR(20) UNIQUE,
                active BOOLEAN NOT NULL DEFAULT FALSE,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT ck_member_name
                    CHECK (char_length(btrim(name)) > 0),
                CONSTRAINT ck_member_phone
                    CHECK (phone IS NULL OR char_length(btrim(phone)) > 0)
            )
        $create_member$;
    ELSIF legacy_table_count <> 4 THEN
        RAISE EXCEPTION
            'V001 rejected partial legacy schema: found % of the required 4 legacy tables',
            legacy_table_count;
    ELSE
        SELECT pg_catalog.string_agg(
                   pg_catalog.format('%I (%s)', relation.relname, relation.relkind),
                   ', '
                   ORDER BY relation.relname
               )
          INTO unexpected_objects
          FROM pg_catalog.pg_class AS relation
          JOIN pg_catalog.pg_namespace AS namespace
            ON namespace.oid = relation.relnamespace
         WHERE namespace.nspname = 'public'
           AND relation.relkind IN ('r', 'p', 'v', 'm', 'f', 'S', 'c')
           AND NOT (
               (
                   relation.relname = ANY (
                       ARRAY[
                           'member',
                           'authentications',
                           'attendance',
                           'attendance_log'
                       ]
                   )
                   AND relation.relkind = 'r'
               )
               OR (
                   relation.relname = ANY (
                       ARRAY[
                           'member_id_seq',
                           'attendance_attend_id_seq',
                           'attendance_log_id_seq'
                       ]
                   )
                   AND relation.relkind = 'S'
               )
               OR (
                   relation.relname = 'flyway_schema_history'
                   AND relation.relkind IN ('r', 'p')
               )
           );

        IF unexpected_objects IS NOT NULL THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema because unexpected relations exist: %',
                unexpected_objects;
        END IF;

        member_oid := pg_catalog.to_regclass('public.member');
        authentications_oid := pg_catalog.to_regclass('public.authentications');
        attendance_oid := pg_catalog.to_regclass('public.attendance');
        attendance_log_oid := pg_catalog.to_regclass('public.attendance_log');
        role_oid := pg_catalog.to_regtype('public.role');
        attend_status_oid := pg_catalog.to_regtype('public.attend_status');

        IF member_oid IS NULL
           OR authentications_oid IS NULL
           OR attendance_oid IS NULL
           OR attendance_log_oid IS NULL THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: all four names must be ordinary public tables';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_class AS relation
         WHERE relation.oid = ANY (
             ARRAY[
                 member_oid,
                 authentications_oid,
                 attendance_oid,
                 attendance_log_oid
             ]
         )
           AND (
               relation.relkind <> 'r'
               OR relation.relpersistence <> 'p'
               OR relation.relispartition
               OR relation.reloftype <> 0
               OR relation.relrowsecurity
               OR relation.relforcerowsecurity
           );

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: legacy tables must be permanent, non-partitioned ordinary tables without RLS';
        END IF;

        IF EXISTS (
            SELECT 1
              FROM pg_catalog.pg_inherits AS inheritance
             WHERE inheritance.inhrelid = ANY (
                       ARRAY[
                           member_oid,
                           authentications_oid,
                           attendance_oid,
                           attendance_log_oid
                       ]
                   )
                OR inheritance.inhparent = ANY (
                       ARRAY[
                           member_oid,
                           authentications_oid,
                           attendance_oid,
                           attendance_log_oid
                       ]
                   )
        ) THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: legacy tables must not use or participate in table inheritance';
        END IF;

        -- This lock closes the time-of-check/time-of-use gap: no concurrent DDL
        -- can change the four tables between exact catalog validation and ALTER.
        -- Preflight replaces only this lock level in memory and then relies on
        -- its READ ONLY transaction to stop at the first following write.
        EXECUTE
            'LOCK TABLE public.member, public.authentications, public.attendance, public.attendance_log IN ACCESS EXCLUSIVE MODE';

        IF role_oid IS NULL OR attend_status_oid IS NULL THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.role and public.attend_status enums are required';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_type AS data_type
          JOIN pg_catalog.pg_namespace AS namespace
            ON namespace.oid = data_type.typnamespace
         WHERE namespace.nspname = 'public'
           AND (
               (
                   data_type.typtype IN ('d', 'e', 'm', 'r')
                   AND NOT (
                       data_type.typtype = 'e'
                       AND data_type.typname IN ('role', 'attend_status')
                   )
               )
               OR (data_type.typtype = 'b' AND data_type.typelem = 0)
           );

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: unexpected user-defined types exist in public';
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM pg_catalog.pg_type AS data_type
             WHERE data_type.oid = role_oid
               AND data_type.typtype = 'e'
        ) OR (
            SELECT pg_catalog.array_agg(
                       enum_value.enumlabel::TEXT
                       ORDER BY enum_value.enumsortorder
                   )
              FROM pg_catalog.pg_enum AS enum_value
             WHERE enum_value.enumtypid = role_oid
        ) IS DISTINCT FROM ARRAY['ADMIN', 'USER']::TEXT[] THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.role must be enum (ADMIN, USER) in that order';
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM pg_catalog.pg_type AS data_type
             WHERE data_type.oid = attend_status_oid
               AND data_type.typtype = 'e'
        ) OR (
            SELECT pg_catalog.array_agg(
                       enum_value.enumlabel::TEXT
                       ORDER BY enum_value.enumsortorder
                   )
              FROM pg_catalog.pg_enum AS enum_value
             WHERE enum_value.enumtypid = attend_status_oid
        ) IS DISTINCT FROM ARRAY['IN_TIME', 'TIME_OUT', 'MISS']::TEXT[] THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attend_status must be enum (IN_TIME, TIME_OUT, MISS) in that order';
        END IF;

        WITH expected (
            attnum,
            attname,
            atttypid,
            atttypmod,
            attnotnull,
            default_rule
        ) AS (
            VALUES
                (1::SMALLINT, 'id', pg_catalog.to_regtype('pg_catalog.int8'), -1, TRUE, 'MEMBER_SERIAL'),
                (2::SMALLINT, 'name', pg_catalog.to_regtype('pg_catalog.varchar'), 259, TRUE, 'NONE'),
                (3::SMALLINT, 'age', pg_catalog.to_regtype('pg_catalog.int4'), -1, FALSE, 'NONE'),
                (4::SMALLINT, 'phone', pg_catalog.to_regtype('pg_catalog.varchar'), 259, FALSE, 'NONE'),
                (5::SMALLINT, 'birth', pg_catalog.to_regtype('pg_catalog.date'), -1, FALSE, 'NONE'),
                (6::SMALLINT, 'created_at', pg_catalog.to_regtype('pg_catalog.timestamp'), -1, FALSE, 'CURRENT_TIMESTAMP'),
                (7::SMALLINT, 'card_uid', pg_catalog.to_regtype('pg_catalog.varchar'), 24, FALSE, 'NONE')
        ),
        actual AS (
            SELECT attribute.attnum,
                   attribute.attname::TEXT AS attname,
                   attribute.atttypid,
                   attribute.atttypmod,
                   attribute.attnotnull,
                   attribute.attcollation,
                   attribute.attidentity,
                   attribute.attgenerated,
                   pg_catalog.pg_get_expr(
                       attribute_default.adbin,
                       attribute_default.adrelid
                   ) AS default_expression
              FROM pg_catalog.pg_attribute AS attribute
              LEFT JOIN pg_catalog.pg_attrdef AS attribute_default
                ON attribute_default.adrelid = attribute.attrelid
               AND attribute_default.adnum = attribute.attnum
             WHERE attribute.attrelid = member_oid
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
        )
        SELECT count(*)
          INTO mismatch_count
          FROM expected
          FULL JOIN actual USING (attnum)
         WHERE expected.attname IS DISTINCT FROM actual.attname
            OR expected.atttypid IS DISTINCT FROM actual.atttypid
            OR expected.atttypmod IS DISTINCT FROM actual.atttypmod
            OR expected.attnotnull IS DISTINCT FROM actual.attnotnull
            OR actual.attcollation IS DISTINCT FROM (
                SELECT data_type.typcollation
                  FROM pg_catalog.pg_type AS data_type
                 WHERE data_type.oid = expected.atttypid
            )
            OR actual.attidentity IS DISTINCT FROM ''
            OR actual.attgenerated IS DISTINCT FROM ''
            OR CASE expected.default_rule
                   WHEN 'NONE' THEN actual.default_expression IS NOT NULL
                   WHEN 'MEMBER_SERIAL' THEN actual.default_expression IS NULL
                       OR actual.default_expression NOT IN (
                           'nextval(''member_id_seq''::regclass)',
                           'nextval(''public.member_id_seq''::regclass)'
                       )
                   WHEN 'CURRENT_TIMESTAMP' THEN
                       actual.default_expression IS DISTINCT FROM 'CURRENT_TIMESTAMP'
                   ELSE TRUE
               END;

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.member columns, order, types, collation, nullability, identity/generated flags, or defaults differ';
        END IF;

        WITH expected (
            attnum,
            attname,
            atttypid,
            atttypmod,
            attnotnull,
            default_rule
        ) AS (
            VALUES
                (1::SMALLINT, 'username', pg_catalog.to_regtype('pg_catalog.varchar'), 54, TRUE, 'NONE'),
                (2::SMALLINT, 'password', pg_catalog.to_regtype('pg_catalog.varchar'), 259, TRUE, 'NONE'),
                (3::SMALLINT, 'authority', role_oid, -1, TRUE, 'NONE')
        ),
        actual AS (
            SELECT attribute.attnum,
                   attribute.attname::TEXT AS attname,
                   attribute.atttypid,
                   attribute.atttypmod,
                   attribute.attnotnull,
                   attribute.attcollation,
                   attribute.attidentity,
                   attribute.attgenerated,
                   pg_catalog.pg_get_expr(
                       attribute_default.adbin,
                       attribute_default.adrelid
                   ) AS default_expression
              FROM pg_catalog.pg_attribute AS attribute
              LEFT JOIN pg_catalog.pg_attrdef AS attribute_default
                ON attribute_default.adrelid = attribute.attrelid
               AND attribute_default.adnum = attribute.attnum
             WHERE attribute.attrelid = authentications_oid
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
        )
        SELECT count(*)
          INTO mismatch_count
          FROM expected
          FULL JOIN actual USING (attnum)
         WHERE expected.attname IS DISTINCT FROM actual.attname
            OR expected.atttypid IS DISTINCT FROM actual.atttypid
            OR expected.atttypmod IS DISTINCT FROM actual.atttypmod
            OR expected.attnotnull IS DISTINCT FROM actual.attnotnull
            OR actual.attcollation IS DISTINCT FROM (
                SELECT data_type.typcollation
                  FROM pg_catalog.pg_type AS data_type
                 WHERE data_type.oid = expected.atttypid
            )
            OR actual.attidentity IS DISTINCT FROM ''
            OR actual.attgenerated IS DISTINCT FROM ''
            OR actual.default_expression IS NOT NULL;

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.authentications columns, order, types, collation, nullability, identity/generated flags, or defaults differ';
        END IF;

        WITH expected (
            attnum,
            attname,
            atttypid,
            atttypmod,
            attnotnull,
            default_rule
        ) AS (
            VALUES
                (1::SMALLINT, 'attend_id', pg_catalog.to_regtype('pg_catalog.int8'), -1, TRUE, 'ATTENDANCE_SERIAL'),
                (2::SMALLINT, 'member_id', pg_catalog.to_regtype('pg_catalog.int8'), -1, FALSE, 'NONE'),
                (3::SMALLINT, 'attend_time', pg_catalog.to_regtype('pg_catalog.timestamp'), -1, FALSE, 'NONE'),
                (4::SMALLINT, 'attend_date', pg_catalog.to_regtype('pg_catalog.date'), -1, FALSE, 'NONE'),
                (5::SMALLINT, 'status', attend_status_oid, -1, TRUE, 'NONE'),
                (6::SMALLINT, 'note', pg_catalog.to_regtype('pg_catalog.text'), -1, FALSE, 'NONE')
        ),
        actual AS (
            SELECT attribute.attnum,
                   attribute.attname::TEXT AS attname,
                   attribute.atttypid,
                   attribute.atttypmod,
                   attribute.attnotnull,
                   attribute.attcollation,
                   attribute.attidentity,
                   attribute.attgenerated,
                   pg_catalog.pg_get_expr(
                       attribute_default.adbin,
                       attribute_default.adrelid
                   ) AS default_expression
              FROM pg_catalog.pg_attribute AS attribute
              LEFT JOIN pg_catalog.pg_attrdef AS attribute_default
                ON attribute_default.adrelid = attribute.attrelid
               AND attribute_default.adnum = attribute.attnum
             WHERE attribute.attrelid = attendance_oid
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
        )
        SELECT count(*)
          INTO mismatch_count
          FROM expected
          FULL JOIN actual USING (attnum)
         WHERE expected.attname IS DISTINCT FROM actual.attname
            OR expected.atttypid IS DISTINCT FROM actual.atttypid
            OR expected.atttypmod IS DISTINCT FROM actual.atttypmod
            OR expected.attnotnull IS DISTINCT FROM actual.attnotnull
            OR actual.attcollation IS DISTINCT FROM (
                SELECT data_type.typcollation
                  FROM pg_catalog.pg_type AS data_type
                 WHERE data_type.oid = expected.atttypid
            )
            OR actual.attidentity IS DISTINCT FROM ''
            OR actual.attgenerated IS DISTINCT FROM ''
            OR CASE expected.default_rule
                   WHEN 'NONE' THEN actual.default_expression IS NOT NULL
                   WHEN 'ATTENDANCE_SERIAL' THEN actual.default_expression IS NULL
                       OR actual.default_expression NOT IN (
                           'nextval(''attendance_attend_id_seq''::regclass)',
                           'nextval(''public.attendance_attend_id_seq''::regclass)'
                       )
                   ELSE TRUE
               END;

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attendance columns, order, types, collation, nullability, identity/generated flags, or defaults differ';
        END IF;

        WITH expected (
            attnum,
            attname,
            atttypid,
            atttypmod,
            attnotnull,
            default_rule
        ) AS (
            VALUES
                (1::SMALLINT, 'id', pg_catalog.to_regtype('pg_catalog.int8'), -1, TRUE, 'ATTENDANCE_LOG_SERIAL'),
                (2::SMALLINT, 'created_at', pg_catalog.to_regtype('pg_catalog.timestamp'), -1, FALSE, 'NOW'),
                (3::SMALLINT, 'member_id', pg_catalog.to_regtype('pg_catalog.int8'), -1, FALSE, 'NONE'),
                (4::SMALLINT, 'uid', pg_catalog.to_regtype('pg_catalog.varchar'), 24, TRUE, 'NONE'),
                (5::SMALLINT, 'result', pg_catalog.to_regtype('pg_catalog.varchar'), 24, TRUE, 'NONE'),
                (6::SMALLINT, 'fail_type', pg_catalog.to_regtype('pg_catalog.varchar'), 54, FALSE, 'NONE'),
                (7::SMALLINT, 'message', pg_catalog.to_regtype('pg_catalog.text'), -1, FALSE, 'NONE')
        ),
        actual AS (
            SELECT attribute.attnum,
                   attribute.attname::TEXT AS attname,
                   attribute.atttypid,
                   attribute.atttypmod,
                   attribute.attnotnull,
                   attribute.attcollation,
                   attribute.attidentity,
                   attribute.attgenerated,
                   pg_catalog.pg_get_expr(
                       attribute_default.adbin,
                       attribute_default.adrelid
                   ) AS default_expression
              FROM pg_catalog.pg_attribute AS attribute
              LEFT JOIN pg_catalog.pg_attrdef AS attribute_default
                ON attribute_default.adrelid = attribute.attrelid
               AND attribute_default.adnum = attribute.attnum
             WHERE attribute.attrelid = attendance_log_oid
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
        )
        SELECT count(*)
          INTO mismatch_count
          FROM expected
          FULL JOIN actual USING (attnum)
         WHERE expected.attname IS DISTINCT FROM actual.attname
            OR expected.atttypid IS DISTINCT FROM actual.atttypid
            OR expected.atttypmod IS DISTINCT FROM actual.atttypmod
            OR expected.attnotnull IS DISTINCT FROM actual.attnotnull
            OR actual.attcollation IS DISTINCT FROM (
                SELECT data_type.typcollation
                  FROM pg_catalog.pg_type AS data_type
                 WHERE data_type.oid = expected.atttypid
            )
            OR actual.attidentity IS DISTINCT FROM ''
            OR actual.attgenerated IS DISTINCT FROM ''
            OR CASE expected.default_rule
                   WHEN 'NONE' THEN actual.default_expression IS NOT NULL
                   WHEN 'ATTENDANCE_LOG_SERIAL' THEN actual.default_expression IS NULL
                       OR actual.default_expression NOT IN (
                           'nextval(''attendance_log_id_seq''::regclass)',
                           'nextval(''public.attendance_log_id_seq''::regclass)'
                       )
                   WHEN 'NOW' THEN actual.default_expression IS DISTINCT FROM 'now()'
                   ELSE TRUE
               END;

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attendance_log columns, order, types, collation, nullability, identity/generated flags, or defaults differ';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_constraint AS constraint_definition
         WHERE constraint_definition.conrelid = member_oid;

        IF mismatch_count <> 2
           OR (
               SELECT count(*)
                 FROM pg_catalog.pg_constraint AS constraint_definition
                WHERE constraint_definition.conrelid = member_oid
                  AND constraint_definition.contype = 'p'
                  AND constraint_definition.conkey = ARRAY[1]::SMALLINT[]
                  AND constraint_definition.convalidated
                  AND NOT constraint_definition.condeferrable
           ) <> 1
           OR (
               SELECT count(*)
                 FROM pg_catalog.pg_constraint AS constraint_definition
                WHERE constraint_definition.conrelid = member_oid
                  AND constraint_definition.contype = 'u'
                  AND constraint_definition.conkey = ARRAY[7]::SMALLINT[]
                  AND constraint_definition.convalidated
                  AND NOT constraint_definition.condeferrable
           ) <> 1 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.member must have only PK(id) and UNIQUE(card_uid)';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_constraint AS constraint_definition
         WHERE constraint_definition.conrelid = authentications_oid;

        IF mismatch_count <> 1
           OR (
               SELECT count(*)
                 FROM pg_catalog.pg_constraint AS constraint_definition
                WHERE constraint_definition.conrelid = authentications_oid
                  AND constraint_definition.contype = 'p'
                  AND constraint_definition.conkey = ARRAY[1]::SMALLINT[]
                  AND constraint_definition.convalidated
                  AND NOT constraint_definition.condeferrable
           ) <> 1 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.authentications must have only PK(username)';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_constraint AS constraint_definition
         WHERE constraint_definition.conrelid = attendance_oid;

        IF mismatch_count <> 3
           OR (
               SELECT count(*)
                 FROM pg_catalog.pg_constraint AS constraint_definition
                WHERE constraint_definition.conrelid = attendance_oid
                  AND constraint_definition.contype = 'p'
                  AND constraint_definition.conkey = ARRAY[1]::SMALLINT[]
                  AND constraint_definition.convalidated
                  AND NOT constraint_definition.condeferrable
           ) <> 1
           OR (
               SELECT count(*)
                 FROM pg_catalog.pg_constraint AS constraint_definition
                WHERE constraint_definition.conrelid = attendance_oid
                  AND constraint_definition.contype = 'u'
                  AND constraint_definition.conkey = ARRAY[2, 4]::SMALLINT[]
                  AND constraint_definition.convalidated
                  AND NOT constraint_definition.condeferrable
           ) <> 1 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attendance PK/UNIQUE/FK structure differs';
        END IF;

        SELECT constraint_definition.conname,
               constraint_definition.confdeltype
          INTO attendance_fk_name,
               attendance_fk_delete_action
          FROM pg_catalog.pg_constraint AS constraint_definition
         WHERE constraint_definition.conrelid = attendance_oid
           AND constraint_definition.contype = 'f'
           AND constraint_definition.conkey = ARRAY[2]::SMALLINT[]
           AND constraint_definition.confrelid = member_oid
           AND constraint_definition.confkey = ARRAY[1]::SMALLINT[]
           AND constraint_definition.confupdtype = 'a'
           AND constraint_definition.confdeltype IN ('c', 'r')
           AND constraint_definition.confmatchtype = 's'
           AND constraint_definition.convalidated
           AND NOT constraint_definition.condeferrable;

        IF attendance_fk_name IS NULL THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attendance must have exactly one valid member_id -> member(id) FK with CASCADE or RESTRICT delete action';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_constraint AS constraint_definition
         WHERE constraint_definition.conrelid = attendance_log_oid;

        IF mismatch_count <> 2
           OR (
               SELECT count(*)
                 FROM pg_catalog.pg_constraint AS constraint_definition
                WHERE constraint_definition.conrelid = attendance_log_oid
                  AND constraint_definition.contype = 'p'
                  AND constraint_definition.conkey = ARRAY[1]::SMALLINT[]
                  AND constraint_definition.convalidated
                  AND NOT constraint_definition.condeferrable
           ) <> 1 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attendance_log PK/FK structure differs';
        END IF;

        SELECT constraint_definition.conname,
               constraint_definition.confdeltype
          INTO attendance_log_fk_name,
               attendance_log_fk_delete_action
          FROM pg_catalog.pg_constraint AS constraint_definition
         WHERE constraint_definition.conrelid = attendance_log_oid
           AND constraint_definition.contype = 'f'
           AND constraint_definition.conkey = ARRAY[3]::SMALLINT[]
           AND constraint_definition.confrelid = member_oid
           AND constraint_definition.confkey = ARRAY[1]::SMALLINT[]
           AND constraint_definition.confupdtype = 'a'
           AND constraint_definition.confdeltype IN ('c', 'r')
           AND constraint_definition.confmatchtype = 's'
           AND constraint_definition.convalidated
           AND NOT constraint_definition.condeferrable;

        IF attendance_log_fk_name IS NULL THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attendance_log must have exactly one valid member_id -> member(id) FK with CASCADE or RESTRICT delete action';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_index AS index_definition
         WHERE index_definition.indrelid = member_oid;

        IF mismatch_count <> 2 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.member has unexpected indexes';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_index AS index_definition
         WHERE index_definition.indrelid = authentications_oid;

        IF mismatch_count <> 1 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.authentications has unexpected indexes';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_index AS index_definition
         WHERE index_definition.indrelid = attendance_oid;

        IF mismatch_count <> 2 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attendance has unexpected indexes';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_index AS index_definition
         WHERE index_definition.indrelid = attendance_log_oid;

        IF mismatch_count <> 1 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: public.attendance_log has unexpected indexes';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_constraint AS constraint_definition
          LEFT JOIN pg_catalog.pg_index AS index_definition
            ON index_definition.indexrelid = constraint_definition.conindid
          LEFT JOIN pg_catalog.pg_class AS index_relation
            ON index_relation.oid = index_definition.indexrelid
         WHERE constraint_definition.conrelid = ANY (
                   ARRAY[
                       member_oid,
                       authentications_oid,
                       attendance_oid,
                       attendance_log_oid
                   ]
               )
           AND constraint_definition.contype IN ('p', 'u')
           AND (
               index_definition.indexrelid IS NULL
               OR NOT index_definition.indisunique
               OR NOT index_definition.indisvalid
               OR NOT index_definition.indisready
               OR NOT index_definition.indislive
               OR NOT index_definition.indimmediate
               OR index_definition.indisexclusion
               OR index_definition.indisprimary
                    IS DISTINCT FROM (constraint_definition.contype = 'p')
               OR index_definition.indnullsnotdistinct
               OR index_definition.indexprs IS NOT NULL
               OR index_definition.indpred IS NOT NULL
               OR EXISTS (
                   SELECT 1
                     FROM pg_catalog.unnest(
                              index_definition.indkey::SMALLINT[]
                          ) WITH ORDINALITY
                              AS indexed_column(attnum, key_position)
                     JOIN pg_catalog.unnest(
                              index_definition.indcollation::OID[]
                          ) WITH ORDINALITY
                              AS indexed_collation(collation_oid, collation_position)
                       ON indexed_collation.collation_position =
                          indexed_column.key_position
                     JOIN pg_catalog.pg_attribute AS indexed_attribute
                       ON indexed_attribute.attrelid = index_definition.indrelid
                      AND indexed_attribute.attnum = indexed_column.attnum
                    WHERE indexed_column.key_position <= index_definition.indnkeyatts
                      AND indexed_collation.collation_oid
                          IS DISTINCT FROM indexed_attribute.attcollation
               )
               OR index_definition.indnkeyatts
                    <> pg_catalog.cardinality(constraint_definition.conkey)
               OR index_definition.indnatts
                    <> pg_catalog.cardinality(constraint_definition.conkey)
               OR index_relation.relam IS DISTINCT FROM (
                   SELECT access_method.oid
                     FROM pg_catalog.pg_am AS access_method
                    WHERE access_method.amname = 'btree'
               )
           );

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: PK/UNIQUE backing indexes must be valid plain btree indexes using column collations without NULLS NOT DISTINCT or INCLUDE columns';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM pg_catalog.pg_trigger AS trigger_definition
         WHERE trigger_definition.tgrelid = ANY (
             ARRAY[
                 member_oid,
                 authentications_oid,
                 attendance_oid,
                 attendance_log_oid
             ]
         )
           AND NOT trigger_definition.tgisinternal;

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: unexpected user triggers exist on legacy tables';
        END IF;

        SELECT count(*)
          INTO mismatch_count
          FROM (
              VALUES
                  ('member_id_seq', member_oid, 1::SMALLINT),
                  ('attendance_attend_id_seq', attendance_oid, 1::SMALLINT),
                  ('attendance_log_id_seq', attendance_log_oid, 1::SMALLINT)
          ) AS expected_sequence(sequence_name, owner_table_oid, owner_attnum)
          LEFT JOIN pg_catalog.pg_class AS sequence_relation
            ON sequence_relation.relnamespace = pg_catalog.to_regnamespace('public')
           AND sequence_relation.relname = expected_sequence.sequence_name
           AND sequence_relation.relkind = 'S'
          LEFT JOIN pg_catalog.pg_sequence AS sequence_definition
            ON sequence_definition.seqrelid = sequence_relation.oid
          LEFT JOIN pg_catalog.pg_depend AS ownership
            ON ownership.classid = pg_catalog.to_regclass('pg_catalog.pg_class')
           AND ownership.objid = sequence_relation.oid
           AND ownership.objsubid = 0
           AND ownership.refclassid = pg_catalog.to_regclass('pg_catalog.pg_class')
           AND ownership.refobjid = expected_sequence.owner_table_oid
           AND ownership.refobjsubid = expected_sequence.owner_attnum
           AND ownership.deptype = 'a'
         WHERE sequence_relation.oid IS NULL
            OR sequence_relation.relpersistence <> 'p'
            OR sequence_definition.seqtypid <> pg_catalog.to_regtype('pg_catalog.int8')
            OR sequence_definition.seqstart <> 1
            OR sequence_definition.seqincrement <> 1
            OR sequence_definition.seqmax <> 9223372036854775807
            OR sequence_definition.seqmin <> 1
            OR sequence_definition.seqcache <> 1
            OR sequence_definition.seqcycle
            OR ownership.objid IS NULL;

        IF mismatch_count <> 0 THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: BIGSERIAL sequence definition or ownership differs';
        END IF;

        IF pg_catalog.pg_get_serial_sequence('public.member', 'id')
               IS DISTINCT FROM 'public.member_id_seq'
           OR pg_catalog.pg_get_serial_sequence('public.attendance', 'attend_id')
               IS DISTINCT FROM 'public.attendance_attend_id_seq'
           OR pg_catalog.pg_get_serial_sequence('public.attendance_log', 'id')
               IS DISTINCT FROM 'public.attendance_log_id_seq' THEN
            RAISE EXCEPTION
                'V001 rejected legacy schema: serial sequence association differs';
        END IF;

        IF EXISTS (
            SELECT 1
              FROM public.member
             WHERE name IS NULL
                OR pg_catalog.btrim(name) = ''
        ) THEN
            RAISE EXCEPTION
                'V001 rejected legacy data: member.name contains null or blank values';
        END IF;

        IF EXISTS (
            SELECT 1
              FROM public.member
             WHERE phone IS NOT NULL
               AND pg_catalog.btrim(phone) = ''
        ) THEN
            RAISE EXCEPTION
                'V001 rejected legacy data: blank member.phone values require approved pre-migration normalization';
        END IF;

        -- Defense in depth for callers that bypass the guarded runner.
        -- Only one-way fingerprints of the former public BCrypt hashes are
        -- retained; account name and authority changes cannot evade this gate.
        IF EXISTS (
            SELECT 1
              FROM public.authentications
             WHERE pg_catalog.encode(
                       pg_catalog.sha256(
                           pg_catalog.convert_to(password::TEXT, 'UTF8')
                       ),
                       'hex'
                   ) IN (
                       '2ea32b4e8d0f2b170a58b152778ddad5630e9b3b2de747beeab7d0fccfd3fbfa',
                       '6b819d3d621468b057b6254132cf3f189df68a0b49060ddc6ec529d47c403ccf'
                   )
        ) THEN
            RAISE EXCEPTION
                'V001 rejected legacy data: a former public sample credential hash is present';
        END IF;

        IF (SELECT max(id) FROM public.member) = 9223372036854775807 THEN
            RAISE EXCEPTION
                'V001 rejected legacy data: member_id_seq cannot advance beyond the maximum BIGINT member id';
        END IF;

        -- Preserve every legacy row and primary key. Only the new operational
        -- columns and checks are added; the sequence is repaired after this block.
        EXECUTE $alter_member$
            ALTER TABLE public.member
                ADD COLUMN active BOOLEAN NOT NULL DEFAULT FALSE,
                ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                ADD CONSTRAINT ck_member_name
                    CHECK (char_length(btrim(name)) > 0),
                ADD CONSTRAINT ck_member_phone
                    CHECK (phone IS NULL OR char_length(btrim(phone)) > 0)
        $alter_member$;

        -- Attendance history must outlive accidental member deletion, so former
        -- CASCADE foreign keys are tightened to RESTRICT without renaming them.
        IF attendance_fk_delete_action = 'c' THEN
            EXECUTE pg_catalog.format(
                'ALTER TABLE public.attendance DROP CONSTRAINT %I',
                attendance_fk_name
            );
            EXECUTE pg_catalog.format(
                'ALTER TABLE public.attendance ADD CONSTRAINT %I FOREIGN KEY (member_id) REFERENCES public.member (id) ON UPDATE NO ACTION ON DELETE RESTRICT',
                attendance_fk_name
            );
        END IF;

        IF attendance_log_fk_delete_action = 'c' THEN
            EXECUTE pg_catalog.format(
                'ALTER TABLE public.attendance_log DROP CONSTRAINT %I',
                attendance_log_fk_name
            );
            EXECUTE pg_catalog.format(
                'ALTER TABLE public.attendance_log ADD CONSTRAINT %I FOREIGN KEY (member_id) REFERENCES public.member (id) ON UPDATE NO ACTION ON DELETE RESTRICT',
                attendance_log_fk_name
            );
        END IF;
    END IF;
END
$v001$;

COMMENT ON COLUMN public.member.age IS
    'Legacy column; not used by the new attendance domain.';
COMMENT ON COLUMN public.member.birth IS
    'Legacy column; not used by the new attendance domain.';
COMMENT ON COLUMN public.member.created_at IS
    'Legacy timestamp without time zone; not used for attendance decisions or statistics.';
COMMENT ON COLUMN public.member.card_uid IS
    'Legacy migration evidence only; nfc_card and nfc_card_assignment are the operational source of truth.';

-- Explicit legacy IDs can leave BIGSERIAL behind max(member.id). Advance only
-- when necessary so the next insert cannot collide with a preserved legacy row.
DO $member_sequence_guard$
DECLARE
    maximum_member_id BIGINT;
    sequence_last_value BIGINT;
    sequence_is_called BOOLEAN;
BEGIN
    SELECT max(id)
      INTO maximum_member_id
      FROM public.member;

    IF maximum_member_id IS NULL THEN
        RETURN;
    END IF;

    EXECUTE
        'SELECT last_value, is_called FROM public.member_id_seq'
       INTO sequence_last_value, sequence_is_called;

    IF (sequence_is_called AND sequence_last_value < maximum_member_id)
       OR (NOT sequence_is_called AND sequence_last_value <= maximum_member_id) THEN
        PERFORM pg_catalog.setval(
            'public.member_id_seq'::REGCLASS,
            maximum_member_id,
            TRUE
        );
    END IF;
END
$member_sequence_guard$;
