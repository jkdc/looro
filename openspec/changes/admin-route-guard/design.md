# admin-route-guard — Design

## Contexto

`createApp` registra las rutas admin en la instancia raíz y en tres módulos de
`http/routes/`. La autenticación vivía en cada handler (`await adminOnly()`,
`await authenticateAdmin(request)`) y el CSRF por `assertAdminOrigin` en los
mutantes.

## Decisiones

- **Guard en `preHandler`** (no `onRequest`): corre tras el admission (que ya
  503a sin panel) y respeta el orden original origin → sesión para mutantes.
- **Opt-out por config de ruta** (`adminPublic: true`) en lugar de una lista de
  paths: la marca viaja con la declaración de la ruta y el test de barrido
  mantiene el conjunto público explícito (cuatro rutas).
- **`request.admin` por declaration merging** (`declare module 'fastify'`):
  tipado sin casts y consumo uniforme en handlers.
- **Canaria en el test de barrido**: registra `/v1/admin/unprotected-canary`
  tras `createApp` para probar que la protección depende del prefijo y no de
  llamadas del handler; cualquier ruta futura sin marca queda cubierta.
- **Enumeración con `printRoutes({ commonPrefix: false })`**: salida plana
  `├── /path (METHOD, HEAD)`; se descarta HEAD (auto-generado) y se acota a
  GET/POST/PUT/DELETE.

## Riesgos

- Doble autenticación eliminada (los módulos de rutas dejan de llamar
  `authenticateAdmin`): una query de sesión menos por petición admin.
- Diferencia menor de orden en GET admin con Origin extranjero y sin sesión:
  antes 403 (page-origin), ahora 401 (guard). Ambos rechazan; el panel nunca
  envía ese caso.
