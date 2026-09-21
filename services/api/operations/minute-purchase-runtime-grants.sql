-- Apply after migrations and the baseline runtime grants, as the migration owner.
REVOKE ALL ON minute_purchase_orders,minute_purchase_events,minute_purchase_transactions FROM looro_runtime;
GRANT SELECT,INSERT ON minute_purchase_orders,minute_purchase_events TO looro_runtime;
GRANT SELECT,INSERT ON minute_purchase_transactions TO looro_runtime;
GRANT UPDATE(state,granted_ms,refunded_minor,reversal_target_ms,recovered_ms,updated_at)
ON minute_purchase_transactions TO looro_runtime;
