CREATE TABLE admin_user (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE board (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES admin_user(id),
    title VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'DELETING')),
    canvas_width INTEGER NOT NULL CHECK (canvas_width > 0),
    canvas_height INTEGER NOT NULL CHECK (canvas_height > 0),
    background_asset_id UUID,
    share_link_version INTEGER NOT NULL DEFAULT 1 CHECK (share_link_version > 0),
    share_token_lookup_hash BYTEA NOT NULL UNIQUE,
    share_token_ciphertext BYTEA NOT NULL,
    share_token_nonce BYTEA NOT NULL,
    share_token_key_version INTEGER NOT NULL CHECK (share_token_key_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX board_owner_id_idx ON board(owner_id);

CREATE TABLE roster_entry (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    encrypted_identity BYTEA NOT NULL,
    identity_nonce BYTEA NOT NULL,
    identity_key_version INTEGER NOT NULL CHECK (identity_key_version > 0),
    identity_hmac BYTEA NOT NULL,
    submitted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT roster_entry_board_identity_hmac_key UNIQUE (board_id, identity_hmac)
);
CREATE INDEX roster_entry_board_id_idx ON roster_entry(board_id);

CREATE TABLE signature_slot (
    id UUID PRIMARY KEY,
    roster_entry_id UUID NOT NULL UNIQUE REFERENCES roster_entry(id) ON DELETE CASCADE,
    placement_status VARCHAR(16) NOT NULL CHECK (placement_status IN ('UNPLACED', 'PLACED')),
    x NUMERIC(9,8),
    y NUMERIC(9,8),
    width NUMERIC(9,8),
    height NUMERIC(9,8),
    background_color VARCHAR(32),
    encrypted_strokes BYTEA,
    strokes_nonce BYTEA,
    strokes_key_version INTEGER CHECK (strokes_key_version > 0),
    slot_revision BIGINT NOT NULL DEFAULT 0 CHECK (slot_revision >= 0),
    submitted_at TIMESTAMPTZ,
    CONSTRAINT signature_slot_geometry_check CHECK (
        (placement_status = 'UNPLACED' AND x IS NULL AND y IS NULL AND width IS NULL AND height IS NULL)
        OR (placement_status = 'PLACED' AND x >= 0 AND y >= 0 AND width > 0 AND height > 0
            AND x + width <= 1 AND y + height <= 1)
    ),
    CONSTRAINT signature_slot_strokes_check CHECK (
        (encrypted_strokes IS NULL AND strokes_nonce IS NULL AND strokes_key_version IS NULL)
        OR (encrypted_strokes IS NOT NULL AND strokes_nonce IS NOT NULL AND strokes_key_version IS NOT NULL)
    )
);

CREATE TABLE background_asset (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    encrypted_object_key BYTEA NOT NULL,
    object_key_nonce BYTEA NOT NULL,
    object_key_key_version INTEGER NOT NULL CHECK (object_key_key_version > 0),
    display_width INTEGER NOT NULL CHECK (display_width > 0),
    display_height INTEGER NOT NULL CHECK (display_height > 0),
    mime_type VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX background_asset_board_id_idx ON background_asset(board_id);
ALTER TABLE board ADD CONSTRAINT board_background_asset_fk
    FOREIGN KEY (background_asset_id) REFERENCES background_asset(id);

CREATE TABLE board_deletion_job (
    id UUID PRIMARY KEY,
    board_id UUID REFERENCES board(id),
    encrypted_object_key BYTEA NOT NULL,
    object_key_nonce BYTEA NOT NULL,
    object_key_key_version INTEGER NOT NULL CHECK (object_key_key_version > 0),
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('BOARD_DELETE', 'BACKGROUND_REPLACED', 'ORPHAN_CLEANUP')),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX board_deletion_job_board_id_idx ON board_deletion_job(board_id);
CREATE INDEX board_deletion_job_status_next_attempt_idx ON board_deletion_job(status, next_attempt_at);
