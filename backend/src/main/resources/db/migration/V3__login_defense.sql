CREATE TABLE admin_login_ip_window (
    trusted_client_ip INET PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL CHECK (attempt_count BETWEEN 1 AND 20)
);
CREATE INDEX admin_login_ip_window_window_started_at_idx ON admin_login_ip_window(window_started_at);

CREATE TABLE login_failure_state (
    client_ip INET NOT NULL,
    canonical_email VARCHAR(254) NOT NULL,
    consecutive_failures INTEGER NOT NULL CHECK (consecutive_failures BETWEEN 1 AND 5),
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_ip, canonical_email)
);
CREATE INDEX login_failure_state_updated_at_idx ON login_failure_state(updated_at);
CREATE INDEX login_failure_state_locked_until_idx ON login_failure_state(locked_until)
    WHERE locked_until IS NOT NULL;
