-- V011: admin notes about a user account (Control Plane).
-- Free-form operator notes with author and time; no user-visible effect.
CREATE TABLE admin_account_notes (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    author VARCHAR(128) NOT NULL,
    note VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_admin_account_notes_account ON admin_account_notes(account_id, created_at DESC);
