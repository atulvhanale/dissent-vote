-- Voting entities: parties, the PM, and MPs. A single table so a vote can target any of them.
CREATE TABLE IF NOT EXISTS entity (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(10) NOT NULL,            -- PARTY | PM | MINISTER | MP
    name        VARCHAR(200) NOT NULL,
    party_name  VARCHAR(200),                    -- for MP / PM / minister rows
    department  VARCHAR(200),                    -- constituency / portfolio detail
    important   BOOLEAN NOT NULL DEFAULT FALSE,  -- govt party, opposition party, PM: pinned to the top
    sort_order  INT NOT NULL DEFAULT 0           -- fixed order among important rows; tie-break otherwise
);

-- Canonical issue bullets shown on the dashboard / offered as typeahead suggestions.
-- Seeded with test data; the AI agent adds new ones as novel reasons come in.
CREATE TABLE IF NOT EXISTS bullet (
    id          BIGSERIAL PRIMARY KEY,
    text        VARCHAR(200) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One vote per mobile number, ever. Changing the selection updates this row.
-- reason = the user's raw free text; bullet = the canonical short bullet the AI mapped it to
-- (null when the AI is disabled — the raw reason is then used as-is).
CREATE TABLE IF NOT EXISTS vote (
    mobile      VARCHAR(20) PRIMARY KEY,
    entity_id   BIGINT NOT NULL REFERENCES entity(id),
    reason      VARCHAR(500) NOT NULL,
    bullet      VARCHAR(200),
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
