# Proposal: public-read-admission

## Why

El análisis de seguridad (informe-seguridad-2026-10-31.md, P1-2) y el propio
código (`app.ts`, comentario «A durable per-client limiter remains follow-up
work») señalaban que `/v1/pricing`, `/v1/minutes/products` y
`/v1/level-tests/{current,generated,hybrid}` quedaban fuera de todo límite:
no pasaban por el limitador en memoria (porque tras el proxy todas las
visitas comparten dirección de socket) ni por ningún limiter durable, aunque
`level-tests/generated|hybrid` consultan la base de datos.

## What Changes

- Nueva operación de admission `'public'` en `AuthAdmission`
  (`network: 3600/h`, `global: 240 000/h`, ventana horaria durable en
  `auth_rate_limits`), con overrides por instancia para tests.
- Nuevo `AuthAdmission.enterGlobal(operation)`: admission solo global para
  peticiones cuya identidad de red no puede autenticarse — un bucket por
  dirección de socket dejaría que un visitante agote el cupo de todos, así
  que el tráfico no atribuible comparte solo el cupo global y nunca falla
  cerrado.
- `app.ts`: las rutas de contenido público entran en admission durable;
  `/readyz` y `/payment-return` quedan explícitamente exentas (sondas y
  retorno de pago).
- Migración `056_public_admission.sql`: amplía el CHECK de
  `auth_rate_limits.operation` con `'public'`.

## Impact

- Affected specs: `api-capacity` / admission de rutas públicas
- Affected code: `src/auth-admission.ts`, `src/app.ts`,
  `migrations/056_public_admission.sql`,
  `tests/public-read-admission.test.ts`
- Compatibilidad: sin cabeceras de proxy válidas las rutas siguen sirviendo
  200 (antes no tenían límite); el comportamiento estricto «requiere
  cabeceras» del deploy guide se activa por configuración del proxy.
