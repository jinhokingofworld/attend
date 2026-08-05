-- Keep catalog metadata aligned with the current application contract without
-- changing the checksum of the already versioned V001 migration.
COMMENT ON COLUMN public.member.age IS
    'Legacy migration evidence only; the new attendance domain does not read or write this column.';

COMMENT ON COLUMN public.member.birth IS
    'Teacher birth date used for birthday management and derived display age; required for registration, basic-information changes, and activation while legacy null rows remain unknown.';
