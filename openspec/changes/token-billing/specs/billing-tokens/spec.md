# Delta Spec: billing-tokens

## ADDED Requirements

### Requirement: Prepaid proxy credit

The system SHALL let an unlocked account spend a prepaid nanoUSD balance on
proxied model calls, metered at the rate version pinned when the credit was
purchased. A call that would exceed the balance SHALL be rejected with a
distinct error after any configured grace, and the account balance SHALL
never go negative through proxy usage.

#### Scenario: unlocked account spends credit

- **WHEN** an unlocked account with balance proxies a call
- **THEN** the provider usage is settled against the balance at the pinned
  rate version and written to the ledger.

#### Scenario: exhausted balance

- **WHEN** an account's balance cannot cover the settled usage beyond the
  grace bound
- **THEN** further proxied calls are refused with `token_balance_exhausted`.

### Requirement: Unlock gate

Proxied calls SHALL require a one-time unlock entitlement delivered through
the existing Play/Stripe purchase paths and reconciled like other purchases.

#### Scenario: locked account cannot proxy

- **WHEN** an account without the unlock entitlement calls the proxy
- **THEN** the response is `unlock_required` and no provider call is made.

### Requirement: Server keys stay server-side

Provider credentials used by the proxy SHALL NOT be delivered to clients.

#### Scenario: proxy responses carry no provider credentials

- **WHEN** any proxy response is inspected
- **THEN** it contains no provider key material.
