CREATE TABLE web_otp_challenges (
    id varchar(64) PRIMARY KEY,
    phone varchar(20) NOT NULL,
    browser_hash varchar(64) NOT NULL,
    code_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    state varchar(12) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX web_otp_browser_idx ON web_otp_challenges(browser_hash);
CREATE TABLE web_auth_limits (
    bucket varchar(80) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    hits integer NOT NULL
);
CREATE TABLE web_customer_sessions (
    token_hash varchar(64) PRIMARY KEY,
    customer_id bigint NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    browser_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX web_session_browser_idx ON web_customer_sessions(browser_hash);