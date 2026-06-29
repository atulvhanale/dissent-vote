-- Voting entities: parties, the PM, and MPs. A single table so a vote can target any of them.
CREATE TABLE IF NOT EXISTS entity (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(10) NOT NULL,            -- PARTY | PM | MP
    name        VARCHAR(200) NOT NULL,
    party_name  VARCHAR(200),                    -- for MP / PM rows
    department  VARCHAR(200),                    -- for MP rows
    sort_order  INT NOT NULL DEFAULT 0
);

-- One vote per mobile number, ever. Changing the selection updates this row.
CREATE TABLE IF NOT EXISTS vote (
    mobile      VARCHAR(20) PRIMARY KEY,
    entity_id   BIGINT NOT NULL REFERENCES entity(id),
    reason      VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Pending OTPs. One active OTP per mobile (replaced on resend).
CREATE TABLE IF NOT EXISTS otp (
    mobile      VARCHAR(20) PRIMARY KEY,
    code        VARCHAR(6) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL
);

-- Time-bound vote tokens issued after OTP verification.
CREATE TABLE IF NOT EXISTS vote_token (
    token       VARCHAR(64) PRIMARY KEY,
    mobile      VARCHAR(20) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL
);
