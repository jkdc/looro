# Proposal: admin-route-guard

## Why

El análisis de seguridad (informe-seguridad-2026-10-31.md, P1-1 y hallazgo
Mimosa «sensitive operations without observed role checks») identificó que la
autenticación del panel admin depende de llamadas manuales a
`adminAuthenticate` dentro de cada handler: 33+ rutas repiten el patrón y una
ruta futura que lo olvide queda pública por defecto. El escaneo estático no
pudo verificar la garantía porque no es estructural.

## What Changes

- Un hook `preHandler` en la raíz de la API aplica, para toda ruta bajo
  `/v1/admin/`: anclaje CSRF por `Origin` en métodos mutantes y autenticación
  de sesión admin. Las cuatro rutas de login (`auth/challenge`, `auth/login`,
  `auth/session`, `auth/sign-out`) se excluyen vía `config.adminPublic`.
- Los handlers consumen `request.admin` inyectado por el guard; desaparecen
  `adminOnly` y las llamadas dispersas a `assertAdminCsrf`/`authenticateAdmin`.
- Test de barrido (`tests/admin-route-guard.test.ts`) que enumera todas las
  rutas admin registradas (vía `printRoutes`) y afirma 401 sin sesión, con una
  canaria registrada sin protección para demostrar que el guard es
  route-agnostic.

## Impact

- Affected specs: `admin-panel` (autenticación de rutas)
- Affected code: `services/api/src/app.ts`,
  `services/api/src/http/routes/admin-level-tests.ts`,
  `admin-level-test-attempts.ts`, `admin-level-test-llm-config.ts`,
  `services/api/tests/admin-route-guard.test.ts`
- Riesgo controlado: el guard es equivalente en orden (origin → sesión) al
  patrón manual existente; suite admin completa en verde tras el cambio.
