-- Apply after migration 038 and the existing billing/voice/helper grants, as the migration owner.
-- The runtime role fulfills pack orders, but only a privileged operator can change entitlements history.
REVOKE ALL ON pack_purchase_quotes,pack_purchase_transactions,entitlements FROM app_runtime;
GRANT SELECT,INSERT ON pack_purchase_quotes,pack_purchase_transactions TO app_runtime;
GRANT UPDATE(state,granted_nano,refunded_minor,reversed_nano,updated_at) ON pack_purchase_transactions TO app_runtime;
GRANT SELECT,INSERT ON entitlements TO app_runtime;
GRANT UPDATE(unlocked_at,unlock_source,unlock_order_id) ON entitlements TO app_runtime;
GRANT SELECT ON rate_versions TO app_runtime;
REVOKE ALL ON FUNCTION verify_pack_quote_link(),protect_pack_purchase_transaction() FROM app_runtime;
