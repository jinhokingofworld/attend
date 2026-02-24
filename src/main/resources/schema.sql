DROP TABLE IF EXISTS attendance;
DROP TABLE IF EXISTS attendance_log;
DROP TABLE IF EXISTS authentications;
DROP TABLE IF EXISTS member;


-- 2. 타입은 테이블 다 지운 뒤
DROP TYPE IF EXISTS role;
DROP TYPE IF EXISTS attend_status;

CREATE TABLE IF NOT EXISTS member (
    id BigSerial PRIMARY KEY, -- ID
    name varchar(255) NOT NULL,
    age integer,
    phone varchar(255),
    birth date,
    created_at timestamp without time zone
        DEFAULT CURRENT_TIMESTAMP,
    card_uid VARCHAR(20) UNIQUE
);

CREATE TYPE role AS ENUM ('ADMIN', 'USER');

CREATE TABLE IF NOT EXISTS authentications (
    username VARCHAR(50) PRIMARY KEY,
    password VARCHAR(255) NOT NULL,
    authority role NOT NULL,
    displayname VARCHAR(50) NOT NULL
);

CREATE TYPE attend_status AS ENUM('IN_TIME', 'TIME_OUT', 'MISS');

CREATE TABLE IF NOT EXISTS attendance (
    attend_id BigSerial PRIMARY KEY,
    member_id BigInt,
    attend_time timestamp without time zone,
    attend_date DATE,
    status attend_status NOT NULL,
    note TEXT,
    CONSTRAINT fk_attendance_member
            FOREIGN KEY (member_id) REFERENCES member(id)
            ON DELETE CASCADE,
            UNIQUE (member_id, attend_date)
);

CREATE TABLE attendance_log (
    id BigSerial PRIMARY KEY,
    created_at TIMESTAMP DEFAULT NOW(),
    member_id BigInt,
    uid VARCHAR(20) NOT NULL,
    result VARCHAR(20) NOT NULL,
    fail_type VARCHAR(50),
    message TEXT,
    CONSTRAINT fk_attendance_log_member
                FOREIGN KEY (member_id) REFERENCES member(id)
                ON DELETE CASCADE
);