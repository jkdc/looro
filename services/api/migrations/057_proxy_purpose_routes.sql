-- Widen the operator audit action enum for actions that arrive without an
-- account (release-hold reconciliation). Purpose routing was removed before
-- shipping (change proxy-model-from-panel), so this migration no longer
-- creates proxy_purpose_routes and the enum never gains set_purpose_route.
ALTER TABLE token_admin_audit DROP CONSTRAINT token_admin_audit_action_check;
ALTER TABLE token_admin_audit ALTER COLUMN account_id DROP NOT NULL;
ALTER TABLE token_admin_audit ADD CONSTRAINT token_admin_audit_action_check
  CHECK (action IN ('grant_unlock','revoke_unlock','grant_balance','grant_quota','release_hold'));
