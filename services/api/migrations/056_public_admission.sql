-- Durable admission for cacheable public content reads (pricing, product
-- catalog, level tests): per visitor network when the proxy authenticates the
-- address, global-only otherwise. Same hourly-window ledger as every other
-- admission operation.
ALTER TABLE auth_rate_limits DROP CONSTRAINT auth_rate_limits_operation_check;
ALTER TABLE auth_rate_limits ADD CONSTRAINT auth_rate_limits_operation_check
  CHECK (operation IN ('challenge','exchange','account','guest','admin','admin-api','public'));
