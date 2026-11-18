-- Daily generation previously hard-required a positive daily budget, so a
-- fresh deployment failed every generation with daily_budget_unavailable.
-- budget_enforced=false lets operators run generation without configuring a
-- budget (reservations are still recorded for audit); default flips the
-- existing singleton off per operator decision.
ALTER TABLE daily_config
  ADD COLUMN budget_enforced boolean NOT NULL DEFAULT true;

UPDATE daily_config SET budget_enforced = false WHERE singleton = true;
