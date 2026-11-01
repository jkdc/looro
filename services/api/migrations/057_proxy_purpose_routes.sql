-- Purpose-based model routing for the token proxy (change proxy-api-provider).
-- The server, not the client, decides which model serves each call purpose:
-- the strong model backs the conversation, the free/flash tier serves the
-- learning loop. Seeded to the current single-provider model so deployment is
-- behavior-preserving; the operator re-routes purposes via tokens-admin.
CREATE TABLE proxy_purpose_routes (
  purpose text PRIMARY KEY CHECK (purpose IN
    ('typed_reply','meaning','assessment','lookup','topic','help','scenario_review','delegation')),
  model text NOT NULL,
  display_name text NOT NULL,
  output_cap integer NOT NULL CHECK (output_cap BETWEEN 64 AND 65536),
  enabled boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO proxy_purpose_routes(purpose, model, display_name, output_cap) VALUES
  ('typed_reply',     'MiniMax-M2', 'MiniMax M2', 4096),
  ('meaning',         'MiniMax-M2', 'MiniMax M2', 2048),
  ('assessment',      'MiniMax-M2', 'MiniMax M2', 2048),
  ('lookup',          'MiniMax-M2', 'MiniMax M2', 2048),
  ('topic',           'MiniMax-M2', 'MiniMax M2', 2048),
  ('help',            'MiniMax-M2', 'MiniMax M2', 2048),
  ('scenario_review', 'MiniMax-M2', 'MiniMax M2', 2048),
  ('delegation',      'MiniMax-M2', 'MiniMax M2', 2048);

-- Route edits and hold reconciliation are operator actions without an
-- account; widen the audit action enum and allow anonymous-of-account rows.
ALTER TABLE token_admin_audit DROP CONSTRAINT token_admin_audit_action_check;
ALTER TABLE token_admin_audit ALTER COLUMN account_id DROP NOT NULL;
ALTER TABLE token_admin_audit ADD CONSTRAINT token_admin_audit_action_check
  CHECK (action IN ('grant_unlock','revoke_unlock','grant_balance','set_purpose_route','grant_quota','release_hold'));
