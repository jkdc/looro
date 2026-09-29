-- Run as the migration owner after migration 023.
-- Operators use a separate privileged connection for reviewed grant/policy commands.
GRANT SELECT ON minute_policy, minute_policy_audit, minute_wallets, minute_entries,
  minute_welcome_offers, minute_welcome_claims, minute_guest_links, minute_reservations,
  minute_campaigns, minute_campaign_recipients, welcome_funding_policy,
  welcome_funding_audit, welcome_funding_allocations TO app_runtime;
REVOKE INSERT, UPDATE, DELETE ON minute_policy, minute_policy_audit, minute_campaigns,
  minute_campaign_recipients, welcome_funding_policy, welcome_funding_audit FROM app_runtime;
GRANT INSERT, DELETE ON minute_wallets TO app_runtime;
REVOKE UPDATE ON minute_wallets FROM app_runtime;
GRANT UPDATE(balance_ms,reserved_ms,sandbox_balance_ms) ON minute_wallets TO app_runtime;
GRANT SELECT ON minute_sandbox_reconciliations TO app_runtime;
REVOKE INSERT,UPDATE,DELETE ON minute_sandbox_reconciliations FROM app_runtime;
GRANT INSERT ON minute_entries, minute_welcome_offers, minute_welcome_claims,
  minute_guest_links, welcome_funding_allocations TO app_runtime;
REVOKE UPDATE, DELETE ON minute_entries, minute_welcome_offers, minute_welcome_claims,
  minute_guest_links, welcome_funding_allocations FROM app_runtime;
-- A verified guest transfer may move claim ownership, never its device proof or allowance.
GRANT UPDATE(account_id) ON minute_welcome_claims TO app_runtime;
GRANT INSERT, UPDATE ON minute_reservations TO app_runtime;
REVOKE DELETE ON minute_reservations FROM app_runtime;
REVOKE ALL ON FUNCTION preserve_public_minute_scope(),check_hosted_public_scope() FROM app_runtime;
GRANT SELECT ON ai_pricing_policy, ai_pricing_audit TO app_runtime;
REVOKE INSERT, UPDATE, DELETE ON ai_pricing_policy, ai_pricing_audit FROM app_runtime;

-- Accepted guest ownership and finalization receipts are append-only; never runtime-editable.
REVOKE ALL ON minute_guest_link_intents,minute_guest_link_completions FROM app_runtime;
GRANT SELECT,INSERT ON minute_guest_link_intents,minute_guest_link_completions TO app_runtime;
