-- Seeds the local lab account and one bearer session for the Android debug
-- sign-in seam (see docs/local-android-lab.md). Not part of the application.
--
-- Input:  :hash  sha256 hex digest of the bearer token (the token itself never
--                 reaches the database, only its hash).
-- Output: one line with the account id, so the caller can print the adb
--         command that hands the bearer to the app.
--
-- Re-running reuses the same email and adds a new session. Sessions are pruned
-- by the server only on sign-out, so old lab sessions expire after 7 days.
WITH existing AS (
  SELECT id FROM accounts
  WHERE email = 'lab@palique.test' AND deleted_at IS NULL
  ORDER BY created_at, id
  LIMIT 1
), created AS (
  INSERT INTO accounts(id, email)
  SELECT gen_random_uuid(), 'lab@palique.test'
  WHERE NOT EXISTS (SELECT 1 FROM existing)
  RETURNING id
), account AS (
  SELECT id FROM existing UNION ALL SELECT id FROM created
), -- The debug seam bypasses the OAuth exchange, which is where signup
   -- creates the wallet (auth.ts). Mirror it, or every proxy grant 404s.
wallet AS (
  INSERT INTO wallets(account_id)
  SELECT id FROM account
  ON CONFLICT (account_id) DO NOTHING
  RETURNING account_id
), session AS (
  INSERT INTO auth_sessions(id, account_id, token_hash, expires_at)
  SELECT gen_random_uuid(), id, :'hash', now() + interval '7 days' FROM account
  RETURNING account_id
)
SELECT account_id FROM session;
