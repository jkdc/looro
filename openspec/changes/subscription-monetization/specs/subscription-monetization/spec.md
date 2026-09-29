# Spec Delta: subscription-monetization

## ADDED Requirements

### Requirement: Plans are operator-editable versioned data

Monthly minutes, rollover caps, free drip parameters, trial length and
per-tier features MUST live in database configuration (`subscription_config`,
`subscription_plans`) changeable by the operator panel with version-conflict
detection, a mandatory reason, and an `admin_actions` audit row. Client
applications MUST NOT hardcode any of these values; they read the effective
configuration from the server.

#### Scenario: Operator raises the Pro monthly minutes

- **WHEN** the operator PUTs the plan config with the current `version` and
  a reason
- **THEN** the config version increments, the change is audited, and
  subsequent clients see the new minutes without any app update.

#### Scenario: Stale version is refused

- **WHEN** the operator PUTs with an outdated `version`
- **THEN** the request fails with 409 and nothing changes.

### Requirement: Periodic grants fill the existing minute wallet

Each active (or trialing) subscription grants its tier's monthly minutes
into the account's minute wallet once per period, idempotently; grants are
capped so the granted balance never exceeds the tier's rollover cap.
Accounts without an active subscription receive the configured free drip
once per period, subject to the same idempotency and the configured
lifetime drip budget. Consumption continues to be enforced exclusively by
the existing hosted reserve→settle flow with a zero floor.

#### Scenario: Renewal grants once

- **WHEN** the renewal grant runs twice for the same period
- **THEN** the wallet receives the monthly minutes exactly once.

#### Scenario: Rollover cap respected

- **WHEN** the granted balance already equals the rollover cap
- **THEN** the period grant is reduced (possibly to zero) rather than
  exceeding the cap.

### Requirement: Client limit UX is advisory, server is authoritative

The mobile client MUST surface remaining minutes, warn at configured
thresholds, and on exhaustion (402) end the turn gracefully, preserve the
resumable session, and present the plan catalog for purchase. The client
MUST NOT be the enforcement mechanism; refusing a session without balance
remains a server decision.

#### Scenario: Balance exhausted mid-conversation

- **WHEN** the server refuses metered voice with `token_balance_exhausted`
- **THEN** the client ends the session state without data loss and shows
  the upgrade sheet; retrying before topping up is refused again by the
  server.

### Requirement: Store billing feeds one wallet

Stripe subscriptions (Checkout subscription mode + lifecycle webhooks) and
Play subscription purchases (verify + acknowledge, poll-sync) both upsert
the same `subscriptions` state and grant through the same path. Webhook and
sync retries MUST be idempotent.

#### Scenario: Stripe webhook retry

- **WHEN** the same `invoice.paid` event is delivered twice
- **THEN** the second delivery changes nothing.
