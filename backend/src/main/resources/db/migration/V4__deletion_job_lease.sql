ALTER TABLE board_deletion_job
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

ALTER TABLE board_deletion_job ADD CONSTRAINT board_deletion_job_lease_check CHECK (
    reason <> 'BOARD_DELETE'
    OR (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_expires_at IS NULL)
);

CREATE INDEX board_deletion_job_claim_idx
    ON board_deletion_job(next_attempt_at, lease_expires_at, created_at)
    WHERE reason = 'BOARD_DELETE' AND status IN ('PENDING', 'PROCESSING');
