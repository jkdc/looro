# Informe de seguridad — Palique (Looro) — 2026-10-31

Análisis de seguridad en profundidad del monorepo combinando **escaneo estático
profundo con Mimosa** y **revisión manual dirigida** de las superficies críticas
(auth, panel admin, criptografía, infraestructura, apps móviles).

---

## 1. Escaneo Mimosa

| Campo | Valor |
|---|---|
| Objetivo | `/home/joaquin/Proyectos/looro-test/looro` (profundidad `deep`) |
| Scan ID | `scan-2026-10-31T08-11-49.988Z-9bc8ff1d3b5b` |
| Sello (seal) | `sha256:51a5ba067ff7e88fdb0a371838d49ff0d4c94da8465998062924a08b8eb34bf2` |
| Artefactos sellados | `~/.mimosa/security-scans/project-1db68906c8f2e2a7cdb4376e/scan-2026-10-31T08-11-49.988Z-9bc8ff1d3b5b/` |
| Estado | `completed` (una sola pasada, sin reintentos), `static_only_no_runtime_execution` |
| Cobertura | 1.362 ficheros seleccionados y parseados (0 fallos), 4.620 funciones, 6.999 aristas de llamada, 80 puntos de entrada, 22 superficies de autorización |
| Vacíos de cobertura declarados | Grafo de llamadas parcial (despacho dinámico / límite de escala); fase de validación parcial (37 de los 250 hallazgos investigados con 283 consultas) |

### Resumen de dependencias

- 150 paquetes escaneados; **0 advisories emparejadas** (advisory offline disponible y aplicada).
- No se detectó ningún paquete con vulnerabilidad conocida.

### Distribución de los 250 findings

- Severidad: **161 high / 89 medium** (sin critical).
- Naturaleza: 183 `cross-file` (flujo de datos entre ficheros), 30 `static`, 37 candidatos de lógica de negocio.
- Veredicto de investigación: 7 `supported_static`, 30 `inconclusive`, 213 sin investigación automática (candidatos estáticos).

---

## 2. Triaje de los findings del escaneo

El desglose por clase, tras verificar cada una contra el código real:

| Clase (CWE) | Nº | Ubicaciones | Veredicto | Justificación |
|---|---|---|---|---|
| Inyección de comando (CWE-78) | 6 | `apps/android/core/engine-local/.../whisper.cpp` (bindings ruby/node, ejemplos, scripts py) | **Vendored, no explotable** | Scripts de build/demo del proyecto vendido; no se ejecutan ni se empaquetan en la app. Ver P2-1. |
| Path traversal (CWE-22) | 14 | 13 en `whisper.cpp` (generadores CUDA/OpenCL/WGSL, conversores de modelos) + 1 en `e2e-evidence/learning-db-phaseD-2026-10-04/ui.py` | **Vendored / evidencia inerte** | Generadores internos del vendor; el fichero de `e2e-evidence` es artefacto de pruebas, no se ejecuta en producción. Ver P2-1 y P2-3. |
| Deserialización insegura (CWE-502) | 2 | `whisper.cpp/models/convert-*.py` (pickle) | **Vendored, no explotable** | Conversores offline de modelos del vendor. Ver P2-1. |
| SQL injection (CWE-89) | ~26 | `transaction`, `recordAdminAction`, `services/api/src/app.ts` y ficheros de rutas | **Falso positivo** | Todo el SQL va parametrizado (`$1…`); los únicos identificadores interpolados provienen de catálogos cerrados del servidor (`admin-browse.ts:24-282` valida tabla/columna/operador contra `BROWSE_TABLES`, valores tipados en `admin-browse.ts:291-308`). |
| SQL f-string | 1 | `scripts/check_db_schema.py:480` | **Higiene (P3-1)** | Herramienta de CI: los nombres interpolados en `PRAGMA` salen del propio `sqlite_master` de la BD local, no de entrada externa. Aun así, conviene validar el identificador. |
| SSRF (CWE-918) | ~25 | `app.ts` (rutas de proxy/voz/helpers), `token-proxy.ts`, `hosted-voice.ts`, admin-ui | **Informativo (P2-4)** | Las URLs de destino (`PROXY_PROVIDER_URL`, `LEVELTEST_LLM_URL`, proveedor de voz) son de **operador/entorno**, no del usuario final. El finding de `app.ts:833` señala que el contenido del usuario fluye al proveedor de voz, que puede ejecutar herramientas web: límite de confianza inherente al diseño, documentable. Ver P2-4. |
| Hardcoded credential (CWE-798) | 1 | `services/api/src/apple-revocation.ts:49` | **Falso positivo** | La línea pasa el literal `'refresh_token'` como `token_type_hint` a Apple; la clave privada ES256 llega por configuración (`.p8` vía env). No hay secreto en el código. |
| XSS (CWE-79) | ~6 | `admin-ui/app.js` (innerHTML) | **Falso positivo con nota** | Escapado sistemático con `escapeHtml` y CSP estricta `default-src 'none'; script-src 'self'` (Caddyfile:18) sin `unsafe-inline`. Mantener disciplina al añadir campos. |
| Permisos ausentes en operaciones sensibles | 37 | todas las rutas `/v1/admin/*` | **Falso positivo actual, riesgo estructural futuro** | La autenticación existe (`adminOnly` dentro de cada handler, `app.ts:356-359`; idéntico en `http/routes/*.ts`) pero es manual y el analizador no la reconoce: si una ruta futura olvida la llamada, queda **pública por defecto**. Este es el hallazgo más accionable del escaneo → P1-1. |
| DTD/XML entity (CWE-776) | 2 | `scripts/check_android_release.py:137,271` | **Higiene (P3-2)** | Parseo XML de artefactos locales con ElementTree estándar; entrada local de confianza, pero conviene blindar. |

**Conclusión del triaje**: no hay ninguna vulnerabilidad explotable identificada en
código de producción propio. El grueso de los 161 "high" proviene de código
vendido de terceros (whisper.cpp) que no forma parte del binario entregado. Los
dos puntos con valor de acción real son **estructurales** (P1-1) y de
**endurecimiento documentado en el propio código** (P1-2).

---

## 3. Revisión manual complementaria

Áreas verificadas a mano y confirmadas como sólidas (referencias al código):

- **Identidad de cliente** (`auth.ts`): tokens de proveedor verificados con `jose` (algoritmo fijado RS256, audiencia/issuer/expiración, `maxTokenAge`, `nonce` con comparación en tiempo constante, `auth.ts:27-33`); desafíos de un solo uso (`auth.ts:64`); portadores de 256 bits guardados solo como SHA-256 (`auth.ts:94-97`); cierre de sesiones y borrado de cuenta con bloqueo de cartera y verificación de pedidos sin liquidar (`auth.ts:128-183`); eliminación de intentos de examen privados en el borrado (`auth.ts:158-160`).
- **Panel admin** (`admin-auth.ts`): cookie `HttpOnly; Secure; SameSite=Strict` + anclaje CSRF por `Origin` (`admin-auth.ts:31-51`); contraseñas con scrypt N=16384 y comparación en tiempo constante con *dummy verification* anti-enumeración (`admin-auth.ts:63-87`); máx. 3 sesiones vivas por operador (`admin-auth.ts:12,94-96`); origen forzado a HTTPS (`admin-auth.ts:22-27`).
- **SQL**: parametrizado de forma consistente en todo `src/`; el motor de browsing construye SQL solo con identificadores de catálogo cerrado y valores validados/tipados (`admin-browse.ts`).
- **Secretos**: sin secretos en git (gitleaks en CI); plantillas con placeholders; claves LLM del panel cifradas AES-256-GCM con clave maestra en env (`llm-config.ts:44-64`) y nunca devueltas en claro (solo `maskKey`, `llm-config.ts:66-67`).
- **Logs**: tipo de campos cerrado a escalares y lista de sufijos sensibles (`account_id`, `email`, `token`, `ip`, …) descartada en `log.ts:9-13`; sin cuerpos de petición ni cabeceras de autorización; acceso HTTP de Caddy desactivado a propósito (`Caddyfile:2,13`).
- **Infraestructura**: contenedor API `read_only`, `cap_drop ALL`, `no-new-privileges` (`compose.yaml:90-93`); puertos de observabilidad solo en `127.0.0.1` (`compose.yaml:104-198`); contraseña de BD exigida por plantilla (`compose.yaml:7`); TLS con HSTS, `nosniff`, `no-referrer` y CSP estricta para el panel (`Caddyfile`).
- **Apps Android**: sin cleartext, sin backup, sin componentes exportados más allá del launcher (`AndroidManifest.xml`); claves de usuario cifradas con Android Keystore AES-GCM (`EngineOverridesStore.kt:29-62`); claves de proveedor en fichero gitignored (`api-keys.properties`) — ver P2-2 para el caso release.
- **Límites y admission**: límites de tarifa por red con claves HMAC y orden de bloqueo determinista (`access-requests.ts:98-120`); admission durable en SQL para auth/guest/admin (`auth-admission.ts:34-40`).

---

## 4. Cambios recomendados y cómo aplicarlos

### P1 — Acciones prioritarias (cierre de clases de riesgo, bajo coste)

#### P1-1. Hacer estructural la autenticación de rutas admin

**Qué**: hoy cada handler admin llama manualmente a `await adminOnly(request)` +
`assertAdminCsrf/ assertPageOrigin` (33+ rutas en `app.ts` y `http/routes/*.ts`).
El escaneo no pudo verificarlo automáticamente y, en la práctica, una ruta nueva
que olvide la llamada queda pública. Mover la garantía al framework.

**Cómo**:
1. Registrar un plugin Fastify (o `app.register` con `prefix: '/v1/admin'`) cuyo
   `onRequest`/`preHandler` aplique `adminAuthenticate` y, para métodos mutantes,
   `assertAdminOrigin`. Las tres rutas de login/session/sign-out llevan una
   marca (p. ej. `config: { adminPublic: true }`) y saltan la autenticación.
2. Cambiar cada handler existente a depender del `request.admin` que inyecta el
   hook (elimina las llamadas dispersas).
3. **Test-first**: antes de tocar nada, añadir en
   `services/api/tests/admin-auth.test.ts` un test exhaustivo que liste
   programáticamente todas las rutas registradas bajo `/v1/admin/` y afirme que
   **todas** responden 401 (o 403/405 para mutantes sin Origin) sin sesión. Ese
   test debe fallar hoy si se registra una ruta sin auth (puedes verificarlo
   registrando temporalmente una ruta dummy sin `adminOnly`).
4. Documentar la regla en `services/api/docs/` para que nuevas rutas usen el
   plugin en lugar de llamadas manuales.

**Verificación**: `npx tsc --noEmit`, `make test-api` (suite completa en stack
efímero), y el nuevo test de barrido. SDD en `openspec/changes/`.

#### P1-2. Límite de tarifa durable para las rutas proxy-exempt

**Qué**: el propio código lo declara como trabajo pendiente
(`app.ts:167-170`: *"A durable per-client limiter remains follow-up work"*).
`/v1/pricing`, `/v1/minutes/products`, `/v1/level-tests/{current,generated,hybrid}`,
`/readyz` y `/payment-return` están fuera del limitador en memoria y fuera del
limiter durable; `level-tests/generated|hybrid` además consultan la BD. Un
cliente abusivo desde una sola IP puede generar carga desproporcionada sin techo.

**Cómo**:
1. Reutilizar el patrón `auth_rate_limits` (ya existe para auth/guest/admin,
   `auth-admission.ts`) con una operación `'public'` y caps generosos por red
   (p. ej. 120/min para catalog/level-tests; `/readyz` exento para sondas).
2. Identidad de red: mismo `trustedClientNetwork` + HMAC ya disponible.
3. **Test-first**: test que golpea el endpoint hasta el cap y afirma 429 +
   `Retry-After`, y que tras la ventana vuelve a 200. Casos: cabeceras proxy
   válidas/ausentes, múltiples IPs simultáneas, reinicio de ventana.
4. Alternativa sin código: módulo de rate limit de Caddy. Se prefiere la
   variante en API para mantener el contrato de errores (`invalid_request`
   / `rate_limit`) y la telemetría `palique_*` coherentes.

**Verificación**: `make test-api`; añadir alerta si procede en
`telemetry/alerts.yml` (ratio `palique_requests_rejected{reason="rate_limit"}`).

### P2 — Endurecimiento (semestre/cuando se toque cada área)

#### P2-1. Política de vendoring de whisper.cpp

Los ~25 hallazgos "high" del escaneo viven en
`apps/android/core/engine-local/src/main/cpp/third_party/whisper.cpp`
(bindings ruby/node/python, generadores CUDA/OpenCL/WGSL, conversores con
pickle). No son explotables, pero contaminan cada auditoría futura y amplían la
superficie de revisión.

**Cómo**: (1) identificar qué consume realmente el CMake/gradle del módulo
`engine-local`; (2) podar del árbol vendido todo lo que no entre en el build
(bindings de otros lenguajes, `examples/`, `models/convert-*`, scripts CUDA);
(3) si no se puede podar (licencia/historial), registrar en un
`docs/vendoring.md` la lista de exclusiones de análisis y el upstream/commit
vendido. Aplicar con commit separado y `make android-build` verde.

#### P2-2. Claves de proveedor en Android: no leerlas de almacenamiento externo en release

`ApiKeysStore.kt:12-21` lee `api_keys.properties` de `getExternalFilesDir`.
En desarrollo es cómodo; en un release, quien tenga acceso al dispositivo puede
depositar el fichero y redirigir el tráfico del motor a su clave/endpoint.

**Cómo**: en producción, embeber la clave por build config (ya existe el canal
`-Papp.conversationEngine` / `api-keys.properties` local del host) y condicionar
la lectura del fichero externo a `BuildConfig.DEBUG` (o a un flag de build).
**Test-first**: test unitario del store que afirme que en release el fichero
externo se ignora. Verificar ambas variantes con `make android-build`.

#### P2-3. Evidencias E2E sin código ejecutable

`e2e-evidence/learning-db-phaseD-2026-10-04/ui.py` es un script dentro del
directorio de evidencias. Moverlo a `scripts/` (o eliminarlo si es desechable) y
dejar `e2e-evidence/` solo para capturas/datos. Commit trivial, sin riesgo.

#### P2-4. Fijar la frontera de confianza de los destinos fetch del servidor

Los únicos destinos de `fetch` salen de env u operador
(`PROXY_PROVIDER_URL`, `LEVELTEST_LLM_URL`, proveedor de voz, jose JWKS).
**Cómo**: (1) exigir `https://` para estos tres en `tokenProxyConfig`,
`resolveLlmConfig` y el config de voz (hoy solo exige URL absoluta,
`token-proxy.ts:32`); (2) documentar en `services/api/docs/` el límite: el
contenido del usuario de voz/helpers llega a un proveedor que puede ejecutar
búsquedas web — restricción de red del contenedor (salida solo a los dominios
necesarios) si el despliegue lo permite. **Test-first**: test que rechaza un
`PROXY_PROVIDER_URL` http con error de arranque.

### P3 — Higiene (oportunidad, no urgencia)

1. **`scripts/check_db_schema.py:480`**: validar que el nombre interpolado en
   `PRAGMA` coincide con `^[A-Za-z_][A-Za-z0-9_]*$` antes de formatear.
2. **`scripts/check_android_release.py:137,271`**: parsear XML sin DTD
   (`xml.etree` con `ParserExpat` que rechace DTD, o `defusedxml`).
3. **`compose.yaml:189`**: en el runbook de producción, exigir
   `GRAFANA_PASSWORD` explícita (hoy hay default `palique-local`, solo loopback).
4. **`app.ts:116-198`**: el limitador en memoria es de instancia única
   (ventana Map 10k/20k) — documentar la restricción de escalado horizontal en
   `docs/` antes de pensar en réplicas.
5. **Caddyfile dominio API**: opcionalmente añadir `Permissions-Policy` y
   `frame-ancestors 'none'`; el API solo devuelve JSON, riesgo mínimo.
6. **Android**: considerar `network_security_config.xml` con certificate
   pinning del dominio API (valorar coste de rotación de certificados).
   **Decisión 2026-10-31**: documentado para añadir **antes del
   lanzamiento** — diseño (CA intermedia + pin de respaldo + expiración),
   pasos de implementación y criterio de aceptación en
   [`docs/plans/2026-10-31-android-certificate-pinning.md`](docs/plans/2026-10-31-android-certificate-pinning.md).

---

## 5. Cómo aplicar el plan (flujo del repositorio)

1. **Rama activa** (`feat/api-performance-hardening` o la que esté en curso) con
   commits convencionales pequeños y verificables; sin atribución de IA.
2. **SDD**: abrir `openspec/changes/` para P1-1 y P1-2 (cambios significativos)
   con `proposal.md`, `design.md`, `tasks.md` y spec de la capacidad afectada.
3. **Test-first obligatorio**: cada item empieza por la prueba que falla por el
   motivo correcto (ver §4). Para la API, los tests corren con
   `make test-api` (PostgreSQL efímero) — no omitir pruebas por necesitar BD.
4. **Verificación antes de cerrar**: `npx tsc --noEmit`, `make test-api`,
   `make check` (exporters/cross-platform/db schema) y, para Android,
   `make android-build`. Reportar explícitamente lo que no se haya podido
   ejecutar.
5. **Orden sugerido**: P1-1 → P1-2 → P2-3 → P2-2 → P2-4 → P2-1 → P3 (P2-3 es
   un commit de cinco minutos y limpia ruido de escaneo).

---

## 6. Límites de este análisis

- El escaneo es estático y no ejecutó el proyecto; su cobertura declarada deja
  el grafo de llamadas parcial (despacho dinámico) y la fase de validación
  parcial (37/250 investigados automáticamente). El triaje manual de este
  informe compensa lo esencial, pero **ninguna combinación estática + manual
  demuestra ausencia de vulnerabilidades**: no se concluye que el proyecto esté
  "completamente seguro".
- No se revisaron en profundidad `shared/` (contenido), el paquete Core iOS
  (no compilable en este entorno) ni el circuito completo de Stripe en vivo.
- Las pruebas con proveedores reales, el piloto con alumnos y cualquier
  despliegue requieren las autorizaciones correspondientes.

---

*Escaneo Mimosa sellado: los artefactos originales (findings.json, report.md,
coverage.json, seal.json) permanecen en el directorio del scan ID indicado en §1
para cualquier re-auditoría o comparación futura.*
