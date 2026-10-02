-- The scenario review helper (post-session structured review of a guided
-- situation practice) joins the hosted helper purposes, mirrored by
-- HOSTED_HELPER_PURPOSES in src/hosted-helpers.ts. Requests are never
-- rewritten, so this is a metadata-only constraint rebuild.
ALTER TABLE hosted_helper_requests DROP CONSTRAINT hosted_helper_requests_purpose_check;
ALTER TABLE hosted_helper_requests ADD CONSTRAINT hosted_helper_requests_purpose_check
  CHECK (purpose IN ('meaning','assessment','lookup','delegation','typed_reply','topic','help','scenario_review'));
