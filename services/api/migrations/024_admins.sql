-- Administrators are a separate principal from app users. Nothing here joins,
-- references or shares storage with accounts, identities or auth_sessions.
CREATE TABLE admins (
  id uuid PRIMARY KEY,
  email text,
  provider text NOT NULL CHECK (provider IN ('google','apple')),
  subject text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  disabled_at timestamptz,
  UNIQUE (provider, subject)
);
CREATE TABLE admin_sessions (
  id uuid PRIMARY KEY,
  admin_id uuid NOT NULL REFERENCES admins(id),
  token_hash text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
CREATE INDEX admin_sessions_admin_idx ON admin_sessions(admin_id);
CREATE INDEX admin_sessions_expiry_idx ON admin_sessions(expires_at);
CREATE TABLE admin_actions (
  id uuid PRIMARY KEY,
  admin_id uuid NOT NULL REFERENCES admins(id),
  action text NOT NULL,
  target text NOT NULL,
  reason text NOT NULL,
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER admin_actions_immutable BEFORE UPDATE OR DELETE ON admin_actions
FOR EACH ROW EXECUTE FUNCTION immutable_ledger();
CREATE INDEX admin_actions_created_idx ON admin_actions(created_at DESC);
