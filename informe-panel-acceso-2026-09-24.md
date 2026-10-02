# Informe: panel de administración en docker — acceso y prueba manual (2026-09-24)

**ACTUALIZACIÓN (mismo día, commit 3b08a41): a petición de Joaquín se eliminó el login de Google.** El panel ahora solo autentica **usuarios internos** creados con el CLI (`npm run admins-admin`), con usuario + contraseña (hash scrypt N=16384, salt por fila). Sin dependencias federadas: sin `GOOGLE_ADMIN_*`, sin callback OAuth, CSP estricta también en el login. Cambio de contraseña: `npm run admins-admin -- passwd <admin-id>` (contraseña por stdin; revoca sesiones vivas). Migración `026_admin_local_auth.sql`. Verificado en navegador: credenciales erróneas → error genérico, login correcto, panel completo funcional, logout OK. Los tests de admin se reescribieron para el flujo local y pasan; el fallo pre-existente de `tests/token-proxy.test.ts` («relay meters…») y el cuelgue al final de la suite existen también en HEAD (verificados con worktree limpio), no son de este cambio.

Credenciales locales de desarrollo: usuario `joaquin`, contraseña `palique-panel-2026-acceso` (cámbiala con `passwd` antes de producción).

---

## Qué había y qué se arregló (estado original)

El panel **ya corría sobre docker** (proyecto compose `api` en `services/api/`: caddy + api + database) y Caddy ya lo servía en `https://admin.localhost` (HTTP 200). Lo que no funcionaba era **entrar**: el `.env` tiene un Google client placeholder (`fake-admin-client.apps.googleusercontent.com`), así que el flujo Google no puede completarse. No se cambió ningún código: el login por **token bearer** (vía prevista en `admin-ui/login.js` para acceso no interactivo) queda como vía de acceso local.

## Cómo acceder

- URL: **https://admin.localhost** (Caddy emite certificado interno; aceptar el aviso del navegador una vez).
- Login: en "Sesión existente (token bearer)" pegar un token de 43 caracteres y pulsar *Crear sesión*.
- Token actual (admin `root@example.test`, caduca en 8 h):
  `vAeKZJbqrceo_OxndXpEuckRjbBrPcynuD6b_3e7Yog`
- Para regenerar cuando caduque:
  ```sh
  cd services/api
  TOKEN=$(node -e "console.log(require('crypto').randomBytes(32).toString('base64url').slice(0,43))")
  HASH=$(node -e "console.log(require('crypto').createHash('sha256').update('$TOKEN').digest('hex'))")
  docker exec api-database-1 psql -U app -d app -c "INSERT INTO admin_sessions(id,admin_id,token_hash,expires_at) VALUES(gen_random_uuid(),'96ae9d4b-50ea-4614-81c3-1dda13c6f9e2','$HASH',now()+interval '8 hours');"
  echo "$TOKEN"
  ```
- Para login Google real faltan (igual que en el informe del 21): OAuth web client de Google + DNS de `admin.$API_DOMAIN`. No es bloqueante para uso local.

## Tema UI

**Tabler** (CSS vendado en `admin-ui/vendor/tabler.min.css`) + **Tabulator** para tablas, skineadas a estilo Tabler en `admin-ui/app.css`; gráficas con **Chart.js**.

## Prueba manual (navegador in-app, 2026-09-24) — todo OK

| Sección | Resultado |
|---|---|
| Login bearer | ✅ redirige a `/` con cookie de sesión |
| Resumen (`#overview`) | ✅ 12 KPIs con datos reales (31 cuentas, 45 min wallets, 1 orden/$10) + 4 gráficas 30d |
| Crecimiento / Voz / Economía | ✅ renderizan, 5-6 gráficas cada una |
| Tablas (`#browse`) | ✅ 16 tablas whitelist; filtro email 31→4 filas server-side; paginación 1/2 y tamaños 10/25/50/100 |
| Menú contextual fila (accounts) | ✅ clic derecho → "Detalle de cuenta" / "Regalar minutos…" |
| Detalle de cuenta | ✅ informe de triaje completo (blockers, órdenes, guest flags) |
| Política bienvenida (`#policy`) | ✅ cambio 15→20 min con motivo obligatorio → versión 2→3; revertido a 15 (v4) |
| Auditoría (`#audit`) | ✅ ambos cambios registrados en `admin_actions` con motivo y detalle JSON |
| Salir | ✅ revoca sesión y vuelve a `login.html` |

## Ronda de pruebas del 24 por la tarde (commit d596314) — 2 problemas reales resueltos

1. **Login fallaba tras 5 min de inactividad** (`login.js` pedía el challenge una sola vez al cargar la página; al caducar, un reintento con credenciales correctas mostraba «Credenciales no válidas»). Ahora el challenge se pide por intento y se refresca una vez si está caducado. Verificado en navegador.
2. **Test `token-proxy` inestable + cuelgue de la suite**: ordenaba el ledger por `id` (UUID aleatorio), así que el settle quedaba último al azar; al fallar, el servidor HTTP del mock nunca se cerraba y el runner se colgaba al salir. Fix: `ORDER BY created_at`. Suite completa tras el fix: **389/389, 0 fallos, sin cuelgue** (typecheck limpio).

El "fallo" del filtro de Tablas detectado durante la pasada resultó ser un artefacto del tooling (Tabulator filtra en `keyup`; los eventos sintéticos no generan teclas). La vía oficial de Tabulator round-trip limpia: filtrar → 5 filas, limpiar → 31 filas y paginación restaurada.

Pasada manual completa (navegador, usuario `joaquin`): las 8 secciones sin ninguna alerta de error; KPIs y gráficas correctas; filtro/paginación/cambio de tabla; menú contextual + detalle de cuenta; campañas renderiza su formulario; política 15→16→15 auditada (v5 y v6 en `admin_actions` con el admin `joaquin@palique.local`); logout OK.

## Estado residual

- Login Google operativo requiere OAuth client real + DNS (pendiente de deploy, no de código).
- Los tokens de sesión se insertan directamente en BD; es la vía pensada para operadores no interactivos, pero en producción conviene que el primer admin real se registre vía `npm run admins-admin` + Google.
- Marca "Palique · Admin" correcta (rebranding final).
