# public-read-admission — Tasks

## 1. Test (RED)

- [x] `tests/public-read-admission.test.ts`: límite durable por red (429 +
  `Retry-After` tras el cap, red distinta conserva cupo), fallback global sin
  identidad, `/readyz` y `/payment-return` nunca limitadas, superficie abierta
  sin admission configurada.
- [x] RED verificado: «a fourth read from one network must be durably
  rejected — 200 !== 429».

## 2. Implementación (GREEN)

- [x] `AuthAdmission`: operación `'public'`, overrides por instancia,
  `enterGlobal()` con mismo ledger y ventana.
- [x] `app.ts`: bloque de admission para `publicContentPaths`; exención
  explícita de `/readyz` y `/payment-return`.
- [x] Migración `056_public_admission.sql` (CHECK con `'public'`).
- [x] GREEN: 4/4 nuevas + suites admin (19), accounts/commerce (37).

## 3. Verificación

- [x] `npx tsc --noEmit`
- [x] Suites afectadas en local (access-requests, accounts, commerce, admin,
  token-proxy, outbound-url): verde.
- [x] `make test-api` completa: 848/848 en verde (stack efímero).
