ALTER TABLE board
    ADD COLUMN signature_ink_color VARCHAR(5) NOT NULL DEFAULT 'black',
    ADD CONSTRAINT board_signature_ink_color_check
        CHECK (signature_ink_color IN ('black', 'white'));

ALTER TABLE signature_slot
    DROP COLUMN background_color;
