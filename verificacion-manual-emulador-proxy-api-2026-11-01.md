# Verificación manual en emulador — conjunto proxy-api-provider

Fecha: 2026-11-01 · Rama `feat/proxy-api-provider` (HEAD `432fbb7`) · Entorno: emulador-5554 + PostgreSQL 17 efímero en local.

## Qué se verificó y cómo

### Ronda A — App Android en el emulador (build debug real, APK instalado)

| # | Verificación | Resultado |
|---|---|---|
| A1 | Onboarding completo (idioma, subtítulos, consentimiento AI) hasta pantalla principal | ✅ |
| A2 | Ajustes → lista de proveedores con el conjunto completo en debug: OpenAI (personal key), DeepSeek, GLM 5.3/4.5, OpenCode, Palique (tokens), **Palique API** | ✅ |
| A3 | Seleccionar **Palique API** → radio marcada | ✅ |
| A4 | **Controles de voz derivados del motor efectivo** (fix rev.5-A1/A3): con `api` seleccionada aparece la sección *Voice-to-text*; al volver a OpenAI desaparece; reaparece al re-seleccionar | ✅ |
| A5 | **Selección reversible** (fix rev.5-A2): se puede volver a OpenAI desde Ajustes y re-seleccionar api | ✅ |
| A6 | **Persistencia**: `shared_prefs/local_engine_selection.xml` contiene `selected=api` tras seleccionar; tras `force-stop` + relanzamiento la radio de Palique API sigue marcada | ✅ (verificado también leyendo el fichero con `run-as`) |
| A7 | Regresión del picker de OpenCode (refactorizado en rev.4/5): catálogo vivo cargado, marcado `(free)` presente (Iceman Contributor Free), botón Refresh | ✅ |

Incidencia durante A6: una primera lectura mostró OpenAI marcada tras reiniciar; reproduce un **tap que no aterrizó en la fila** (verificado reintentando: el store ya contenía `selected=api` y el reinicio lo preservó). No es defecto de la app.

### Ronda B — Flujo backend real de extremo a extremo (HTTP real contra la app real con proveedor stub)

Driver: `/tmp/e2e-proxy-manual.mts` (no versionado — verificación manual). DB PostgreSQL efímera con migraciones reales, `createApp` real (misma composición que `main.ts`: admission + policy + token proxy), proveedor stub grabando lo recibido. **26/26 verificaciones ok**:

- **Policy endpoint** (lo que consume la app): etiquetas por propósito, `limitTokens` del plan, consumo del día 0, cuenta sin plan cae al allowance gratuito (150).
- **Gate de unlock**: cuenta bloqueada → `403 unlock_required` sin tocar el proveedor.
- **Routing por propósito**: `typed_reply` reenvía el modelo sembrado con cap del servidor y `stream:false`; tras `tokens-admin set-route meaning → free-flash-x/512`, la llamada `meaning` reenvía el modelo nuevo con su cap, y el policy endpoint publica la etiqueta nueva (coherencia B6: set-route → proxy → policy → llamada, la sonda manual pendiente de las rondas anteriores).
- **Metering**: 1M+1M tokens → débito exacto de 1_500_000n y campo `metering` en la respuesta.
- **Política de petición**: campo fuera de allowlist → `400 request_policy_violation` sin llamar al proveedor; propósito desconocido → `400 unknown_purpose`; falta de header con `PROXY_REQUIRE_PURPOSE=true` → `400 purpose_required`.
- **Retenciones**: rechazo upstream (401) → `502 provider_error` al cliente + retención liberada; deadline vencido → error + retención **conservada**; `tokens-admin release-hold` (conciliación auditada) la libera.
- **Agotamiento**: la balance llega a cero → `402 token_balance_exhausted`; la llamada 402 nunca llega al proveedor; suelo cero sin negativos (98 llamadas financiadas exactas sobre 147M).

### Lo que NO se verificó aquí (sigue pendiente)

- **Conversación real de voz** contra proveedor real (requiere clave del proveedor y sesión de cuenta Google real — el driver B usa el mismo contrato HTTP pero sin realtime).
- **Wiring App de iOS** (gate macOS).
- **Perfil de carga con propósitos** (Task 8.1).
- Compras live / suscripción recurrente (change futuro).

## Conclusión

El conjunto implementado — provider `api`, routing por propósito, cuota con grants, reserva de fondos, conciliación, y los ajustes Android de las 4 rondas de auditoría — **se comporta en el emulador y en el backend real como se espera**, cubriendo también la sonda manual que quedaba pendiente de las rondas de auditoría. El cierre completo para producción sigue dependiendo de los 4 puntos anteriores.
