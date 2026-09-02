ALTER TABLE signature_slot
    ADD COLUMN active_signer_claim UUID,
    ADD COLUMN active_signer_claim_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT signature_slot_active_claim_check CHECK (
        (active_signer_claim IS NULL AND active_signer_claim_expires_at IS NULL)
        OR (active_signer_claim IS NOT NULL AND active_signer_claim_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT signature_slot_active_claim_submitted_check CHECK (
        submitted_at IS NULL OR active_signer_claim IS NULL
    );

CREATE INDEX signature_slot_active_signer_claim_expires_at_idx
    ON signature_slot (active_signer_claim_expires_at)
    WHERE active_signer_claim IS NOT NULL;
