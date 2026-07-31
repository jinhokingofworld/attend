CREATE TABLE member (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    age INTEGER,
    phone VARCHAR(255),
    birth DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    card_uid VARCHAR(20) UNIQUE
);

CREATE TYPE role AS ENUM ('ADMIN', 'USER');

CREATE TABLE authentications (
    username VARCHAR(50) PRIMARY KEY,
    password VARCHAR(255) NOT NULL,
    authority role NOT NULL
);

CREATE TYPE attend_status AS ENUM ('IN_TIME', 'TIME_OUT', 'MISS');

CREATE TABLE attendance (
    attend_id BIGSERIAL PRIMARY KEY,
    member_id BIGINT,
    attend_time TIMESTAMP WITHOUT TIME ZONE,
    attend_date DATE,
    status attend_status NOT NULL,
    note TEXT,
    CONSTRAINT fk_attendance_member
        FOREIGN KEY (member_id) REFERENCES member (id)
        ON DELETE CASCADE,
    UNIQUE (member_id, attend_date)
);

CREATE TABLE attendance_log (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP DEFAULT NOW(),
    member_id BIGINT,
    uid VARCHAR(20) NOT NULL,
    result VARCHAR(20) NOT NULL,
    fail_type VARCHAR(50),
    message TEXT,
    CONSTRAINT fk_attendance_log_member
        FOREIGN KEY (member_id) REFERENCES member (id)
        ON DELETE CASCADE
);
