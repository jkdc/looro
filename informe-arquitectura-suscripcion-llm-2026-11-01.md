# Análisis: modelo de suscripción, control de consumo y seguridad de claves — llamada al LLM desde la app móvil

Fecha: 2026-11-01 · Complementa: `informe-analisis-tokens-suscripcion-2026-11-01.md` (estado actual del token proxy) y `openspec/changes/audit-token-proxy-safety/`.

## 1. Resumen ejecutivo — recomendación

**El proxy en el backend no es opcional: es el único diseño que resuelve tus tres preocupaciones a la vez** (claves seguras, control de consumo y elección de provider/modelo por el BE). La buena noticia: **ya está construido y probado** — el token proxy existente es exactamente esa pieza; lo que falta definir es la capa de suscripción/cuotas encima y la política de routing.

| Pregunta | Conclusión corta |
|---|---|
| ¿Puedo poner las API keys en la app? | **No** para proveedores de pago. Una clave en una app es pública (§2). Para tiers gratuitos *también* conviene el proxy, por control y por ToS. |
| ¿Necesito proxy para controlar tokens? | **Sí.** El consumo solo se controla donde viven la clave y la decisión de despachar. En el dispositivo todo límite es decorativo (se parchea). |
| ¿Cuánto cuesta en latencia? | Un salto más ≈ 10–50 ms frente a 1,2–2,0 s de TTFT del modelo (medido en este repo). La palanca real de latencia es **streaming**, no eliminar el proxy. |
| ¿Voz de pago + resto gratis? | **Sí, y la app ya tiene el gancho**: cada llamada lleva `HelperPurpose` (§6). |
| ¿Cuántos tokens por usuario? | Fórmula y números de partida en §5; el coste por turno es ~$0,001 con la tarifa semilla — el margen de una cuota es enorme. |

## 2. El problema real: una clave en la app es una clave pública

Asumir lo contrario es la vía rápida a una factura de cinco cifras. Vías de extracción, todas baratas:

1. **Ingeniería inversa del APK**: el bytecode se descompila con herramientas gratuitas; cualquier constante (incluida una clave en `BuildConfig`, `api-keys.properties` empaquetado, o NDK) sale a la luz en minutos.
2. **Inspección de tráfico**: un proxy HTTPS local (mitmproxy, Charles) con certificado de usuario instalado en un emulador o dispositivo propio muestra cada request/response en claro. Cert pinning lo dificulta, pero en dispositivos rooteados (Frida/objection) se bypasea; y basta **un** usuario técnico para extraer la clave y publicarla.
3. **Reenvío directo**: una vez extraída, la clave se usa sin tu app, sin tus límites y sin tu knowledge. No hay telemetría que te avise.

Además de las claves, **el control de consumo en cliente también es decorativo**: un contador local de tokens se parchea igual que se parchea una verificación de licencia. La atestación de app (Play Integrity) ayuda a comprobar que quien llama es tu app genuina, pero nunca sustituye al control en servidor, porque la clave una vez filtrada no distingue de dónde viene.

**Conclusión**: para cualquier proveedor de pago (y para gratuitos con cuota que quieras dosificar), la clave vive solo en el servidor y las llamadas pasan por tu proxy. Es el patrón que usa todo el sector (Cursor, Perplexity, Duolingo…): la app habla con tu backend; tu backend habla con el LLM.

### ¿Y la latencia que motiva "directo desde la app"?

El argumento de velocidad merece números, no intuición:

- Coste del salto extra (app → BE → proveedor): keep-alive TLS ya establecido o un handshake extra ≈ **10–50 ms** si el BE está en la misma región que el proveedor.
- Latencia dominante real: **TTFT del modelo**, 1,2–2,0 s medidos en este repo (GLM 4.5-flash en vivo), y el tiempo de generación completo en no-streaming.
- La verdadera palanca de latencia percibida en voz es **streaming** (empezar a sintetizar TTS con los primeros tokens). Hoy el contrato v1 del proxy rechaza `stream:true` porque el metering llega al final; es ampliable (§7) y da mucho más que eliminar el proxy.

Es decir: eliminar el proxy compra ~1–3% de la latencia total y pierde el 100% del control.

## 3. Opciones consideradas

### A. Directo desde la app con claves embebidas — descartada
Claves extraíbles, consumo incontrolable, imposible cambiar provider sin publicar app, imposible revocar una clave filtrada sin romper a todos los usuarios. Descartada para producción (vale paraExperimentación BYOK personal: el motor "OpenAI (personal key)" actual, donde el usuario pone *su propia* clave — eso sí es seguro porque la clave es del usuario).

### B. Proxy total (recomendada)
La app siempre llama al BE (`/v1/proxy/responses`); el BE decide provider/modelo, guarda las claves, mide, factura y aplica cuotas. La selección de providers que hoy existe en Ajustes desaparece en el build de producción (queda como superficie de desarrollo, igual que hoy).

Ventajas sobre lo que ya tienes:
- El token proxy actual ya trae: auth de sesión, unlock, admisión por saldo/estimación, límites de concurrencia, liquidación exactly-once en ledger inmutable, telemetría. La "suscripción" es una capa pequeña encima (§5).
- "El BE le dice a la app qué provider/modelo usar" se vuelve trivial y seguro: la app ni se entera — el BE elige en el momento del reenvío. Cambiar de modelo = cambiar una fila de configuración en el servidor, sin publicar app.
- Failover, rotación de claves y multi-cuenta son decisiones de servidor.

### C. Híbrido: gratis directo, pago vía proxy — posible pero no recomendada
Los tiers gratuitos también suelen exigir clave (que se extraería igual), su cuota la quemaría un solo abusers que extraiga la clave, y perderías la telemetría de la mitad del tráfico. Si el objetivo del "gratis directo" es ahorrar coste de servidor: el ahorro es ~0 (el proxy es un reenvío stateless barato). **Si acaso**, el híbrido solo tiene sentido con proveedores gratuitos *sin clave* autohospedados por ti — y entonces sigues queriendo el proxy delante por la misma razón.

## 4. Arquitectura objetivo (sobre lo existente)

```
App ── bearer sesión ──►  BE
                          ├─ política: ¿qué propósito/tier? ──► routing table (servidor)
                          ├─ admisión: sesión + suscripción + cuota del día (ledger)
                          ├─ proxy: reenvía al provider elegido (clave solo aquí)
                          └─ metering: usage real → ledger inmutable → cuota consumida
```

Piezas nuevas sobre el proxy actual:

1. **Endpoint de política** (p. ej. incluido en `GET /v1/wallet` o un `GET /v1/conversation-policy`): `{model, maxTokens, tier, quotaRemaining}`. La app lo usa solo para pintar UI ("te quedan X turnos hoy"); la decisión real la toma el proxy.
2. **Routing por propósito**: la app ya envía `HelperPurpose` en cada llamada (`ConversationClient.kt`: `TYPED_REPLY`, `MEANING`, `ASSESSMENT`, `LOOKUP`, `TOPIC`, `HELP`, `SCENARIO_REVIEW`, `DELEGATION`). Hoy el proxy lo ignora (reenvía el body tal cual). Basta un header/campo `x-palique-purpose` validado contra una tabla servidor: `TYPED_REPLY → provider pago, modelo potente` · `MEANING/LOOKUP/… → provider gratis/flash`. La tarifa también puede variar por propósito (o el gratuito costar 0).
3. **Cuotas de suscripción sobre el ledger existente**: la cuota mensual se materializa como un *grant* diario (entrada de ledger `grant` con expiración, análoga a los `campaign-grant` ya implementados en `tokens-admin`). El saldo disponible ya existente (`balance − reserved`) ES la cuota restante; la admisión 402/estimación ya la respeta. Sin esquema nuevo de dinero, solo un cron de grants + filas de `subscriptions`.
4. **Atestación (fase 2)**: Play Integrity en el proxy para exigir app genuina en las rutas de consumo; reduce abuso de cuentas guest reales.

## 5. Control de consumo: cuántos tokens

Mecánica ya existente que puedes activar por usuario: estimación pre-llamada (rechaza si la estimación excede el saldo), `PROXY_MAX_TOKENS` (tope de salida por llamada), `PROXY_MAX_CONCURRENT` (2/account), gracia acotada (una llamada nunca deja saldo negativo). Lo que falta definir es la **asignación por suscripción**:

- Coste real por turno con la tarifa semilla ($0,30/M in · $1,20/M out), turno de voz típico ~2.000 in + 300 out ≈ **$0,001/turno** (~$0,03 por sesión de 30 turnos). Un pack de $4,99 acredita $2,16 ≈ **2.000+ turnos**; el pricing original del change `token-billing` hablaba de ~300 sesiones por pack con margen.
- Con esa cifra, una cuota de **100–300 turnos/día** por suscriptor cuesta <$0,10/mes por usuario activo incluso todo en modelo de pago — el techo no lo pone el coste, sino el abuso. Sugerencia de partida: `200 turnos/día` equivalente en tokens (p. ej. 400k in + 60k out/día), revisable con los datos reales de `proxy_usage` (el panel admin ya agrega `input_tokens/output_tokens/settled_nano` por día).
- Topes por llamada ya en sitio (`PROXY_MAX_TOKENS=4000`) evitan que un solo turno devore la cuota diaria.
- Los límites de free-tier del proveedor se gestionan **en el servidor** (colas, backoff, failover a otro modelo) — nunca en el cliente.

## 6. "Voz de pago, resto gratis" — sí, así de barato

El split que propones encaja con la estructura de costes real del producto:

- **`TYPED_REPLY` (y la conversación de voz, que usa el mismo motor)** = la experiencia estrella → modelo potente (pago o flash bueno). Es además donde la latencia se siente.
- **`MEANING`, `LOOKUP`, `ASSESSMENT`, `TOPIC`, `HELP`, `SCENARIO_REVIEW`** = muchas llamadas cortas, tolerantes a latencia → modelo gratuito/flash del gateway (el catálogo opencode que ya probamos en la app trae modelos a coste 0 detectables por precio).
- El servidor enruta por propósito, no la app: si mañana el modelo gratuito desaparece o se degrada, cambias la tabla de routing sin tocar la app.

Riesgo a vigilar: el *learning loop* (meanings/assessments) usa salida estructurada (`response_format json_object`); verifica que el modelo gratuito elegido cumple JSON mode de forma fiable antes de mandarle tráfico crítico.

## 7. Multi-cuenta y modelos gratuitos a escala

Rotar varias cuentas gratuitas para eludir límites tiene tres problemas: **ToS** (la mayoría de proveedores lo prohíben explícitamente y banean), **fiabilidad** (un ban a mitad de partida de usuarios = degradación visible), y **opacidad** (no hay contrato de capacidad). Alternativas más sanas, en orden:

1. Modelos **gratuitos de catálogo** diseñados para esto (los "* Free" del gateway ya los etiquetan y los detecta tu app).
2. **Créditos de startup** de los proveedores (Zhipu/OpenAI/Anthropic los dan sin compromiso de ToS dudoso).
3. **Rate-limit server-side** sobre tu propio pool de claves del mismo proveedor de pago (varias claves legítimas para repartir carga — compruébalo contra el ToS concreto, suele estar permitido si son tuyas y pagas) con failover automático.
4. Autohospedar un modelo pequeño (Qwen/Llama en una GPU) para los propósitos "gratis": coste fijo, cero ToS externo, control total. A POC de carga lo hace atractivo para `MEANING/LOOKUP`.

## 8. Streaming: la mejora de latencia que de verdad importa

Hoy v1 es no-streaming: el usuario espera la respuesta completa antes de oírla. Para voz, esto pesa más que todo lo demás. Camino recomendado (fase posterior, no bloqueante para el lanzamiento):

- El proxy acepta `stream:true` y reenvía SSE; el metering se hace con el **chunk final de usage** (`stream_options.include_usage`, soportado por las APIs OpenAI-compatibles que ya usas) — el ledger no cambia.
- La app empieza TTS con los primeros tokens → latencia percibida baja de ~2–4 s a <1 s.
- El fail-closed actual se conserva: si el stream muere sin usage, se liquida por evidencia disponible o queda `unknown` (la reconciliación del audit cubre este caso).

## 9. Qué falta implementar (gap list priorizada)

| # | Pieza | Estado hoy | Esfuerzo |
|---|---|---|---|
| 1 | Reserva atómica de fondos antes de despachar (audit F05) | Proposal, sin implementar | Medio — bloqueante para dinero real |
| 2 | Política de petición: allowlist de campos + modelo fijado por el servidor (F06–F07) | Hoy se reenvía tal cual con modelo del cliente | Bajo-Medio — se necesita para el routing por propósito |
| 3 | Grants de suscripción (cron + filas `subscriptions`) sobre ledger | `tokens-admin` grants manuales ya existen | Bajo |
| 4 | Routing por `HelperPurpose` en el proxy | La app ya lo envía; el proxy lo ignora | Bajo |
| 5 | Endpoint de política para la app (UI de cuota restante) | No existe | Bajo |
| 6 | Streaming con metering al final | v1 lo rechaza explícitamente | Medio — fase 2 |
| 7 | Reconciliación de `unknown` (runbook + tarea) | Persistidos, sin proceso | Bajo |
| 8 | Alertas `palique.proxy.*` | Ninguna | Muy bajo |
| 9 | Retirar selección de providers y BYOK del build de producción | Superficie dev útil hoy | Trivial (flags) |

## 10. Plan de fases sugerido

1. **Fase 0 (ahora)**: cerrar audit-token-proxy-safety (1–2) + alertas (8) + runbook `unknown` (7). Sin esto, no encender dinero real.
2. **Fase 1 (MVP de suscripción)**: grants de cuota (3) + endpoint de política (5) + routing por propósito con un solo proveedor de pago y modelos free de catálogo para el resto (4, 6-de-scoping). App: producción sin selector de providers (9). Piloto con `tokens-admin grant-unlock`.
3. **Fase 2 (escala)**: streaming (6), atestación Play Integrity, failover multi-clave, y evaluación de autohospedado para los propósitos gratuitos con los datos reales de `proxy_usage`.

## 11. Ficheros de referencia

- `services/api/src/token-proxy.ts` · `proxy-attempts.ts` · `ledger.ts` — el metering que reutilizas tal cual
- `docs/token-billing.md` — contrato actual (fórmula, errores, config)
- `openspec/changes/audit-token-proxy-safety/proposal.md` — F05–F07, la base de la fase 0
- `apps/android/.../network/ConversationClient.kt` — `HelperPurpose`, el gancho del routing
- `services/api/src/pack-products.ts` · `tokens-admin.ts` — dinero y grants que la suscripción extiende
- `informe-analisis-tokens-suscripcion-2026-11-01.md` — estado y verificación del proxy (848/848)
