-- Usage metrics belong to their account: a completed deletion takes its events
-- with it. 028's plain FK would have blocked the signup-only hard-delete path in
-- auth.ts the first time an account carried usage events.
ALTER TABLE usage_events DROP CONSTRAINT usage_events_account_id_fkey;
ALTER TABLE usage_events ADD CONSTRAINT usage_events_account_id_fkey
  FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
