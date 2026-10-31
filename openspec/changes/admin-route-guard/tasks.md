# admin-route-guard — Tasks

## 1. Test de barrido (RED)

- [x] `tests/admin-route-guard.test.ts`: enumerar rutas `/v1/admin/*` con
  `printRoutes({ commonPrefix: false })`, canaria sin protección, afirmar 401
  (403 con Origin extranjero en las cuatro rutas públicas).
- [x] Verificar RED: la canaria responde 200 antes del guard.

## 2. Guard central (GREEN)

- [x] `app.ts`: hook `preHandler` con `assertAdminOrigin` (mutantes) +
  `adminAuthenticate`; `FastifyRequest.admin` y `FastifyContextConfig.adminPublic`
  por declaration merging.
- [x] Marcar `auth/challenge`, `auth/login`, `auth/session`, `auth/sign-out`
  con `config: { adminPublic: true }`.
- [x] Sustituir `adminOnly`/`assertAdminCsrf` en handlers por `request.admin`.
- [x] Actualizar `http/routes/admin-level-tests.ts`,
  `admin-level-test-attempts.ts`, `admin-level-test-llm-config.ts`.
- [x] Verificar GREEN: barrido + suites admin (25) + rutas registradas (106).

## 3. Verificación

- [x] `npx tsc --noEmit`
- [ ] `make test-api` (suite completa) al cierre de la rama
