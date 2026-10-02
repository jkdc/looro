# Informe de batería manual Fase 2 + Fase 3 — emulador y navegador

**Fecha:** 2026-09-22 (tarde) · **Rama:** `feature/rebranding-looro` · **Commits:** `8d10abe` (SRS), `6745333`+`9f74da3` (billing backend), `babcc9e` (records).
**Alcance:** batería manual sobre las implementaciones de la Fase 2 (token-billing backend) y Fase 3 (spaced-recall-notebook) ejecutadas en esta sesión, tras sus suites automáticas.

---

## 1. Fase 3 en el emulador — SRS y libreta de vocabulario

Preparación: rebuild + deploy del build SRS (GLM), `pm clear` para instalación desde cero.
Hallazgo de proceso: el primer deploy corrió `make` en el directorio equivocado (APK viejo del mediodía) — el flujo se repitió con el APK correcto (21:19).

| # | Prueba | Resultado | Evidencia |
|---|---|---|---|
| 1 | Onboarding fresco + práctica L1 con GLM (pregunta numerada del guion) | ✅ | `41`–`42` |
| 2 | Lookup de palabra en sesión → **botón nuevo "Save for recall"** guarda la expresión | ✅ | captura en vivo |
| 3 | Tutor se presenta como **"I'm Palique"** (fix del prompt de la Fase 1, en vivo) | ✅ | transcripción |
| 4 | Fin de sesión siembra las 6 expresiones objetivo de L1 (idempotente) | ✅ | `44` |
| 5 | **Tarjeta "Recall — 7 expressions ready for review"** en Learn (6 seed + 1 lookup) | ✅ | `45` |
| 6 | Sesión de repaso: contador "1 of 7", prompt por significado, campo de respuesta | ✅ | `46` |
| 7 | Grading tipado: "hello" == "Hello" → **"Correct!"** + Continue | ✅ | `47` |
| 8 | Notebook: sección "Saved expressions" en Words con barras (Hello subió a bar 1), Remove por tarjeta | ✅ | `48` |
| 9 | Estabilidad: sin crashes durante el flujo; el 429 de GLM se degrada con diálogo claro (no bug de app) | ✅ | logcat |

## 2. Fase 2 en el navegador/API — token-billing backend

Entorno: api reconstruido con `PROXY_PROVIDER_*` apuntando a un proveedor stub (contenedor efímero), migración 025 aplicada, cuenta de batería con wallet de 50M nano.

| # | Prueba | Resultado |
|---|---|---|
| B1 | Token inválido → **401** `sign_in_required` | ✅ |
| B2 | Sesión válida sin entitlement → **403** `unlock_required` (no se llama al proveedor) | ✅ |
| B3 | Con unlock → **200**, reply del proveedor, metering `{"rateVersion":"2026-09-minimax-m","nanoUSDSettled":"90000"}` (100k in × 0.3 + 50k out × 1.2) | ✅ |
| B4 | Wallet 50,000,000 → **49,910,000** (débito exacto en ledger `settle`) | ✅ |
| B5 | Coste > saldo → **grace acotado**: settle al saldo (nunca negativo) y balance 0 | ✅ |
| B6 | Saldo cero → **402** `token_balance_exhausted` antes de llamar al proveedor | ✅ |
| B7 | Panel (navegador): tabla **entitlements** en Tablas con el unlock de la batería (campaign, hoy) | ✅ `49` |

## 3. Estado y pendientes honestos

- **spaced-recall-notebook:** Android completo y probado con usuario real (emulador). El change queda ABIERTO por la **UI iOS** (el core Swift y los tests espejo ya están escritos; requieren gate de macOS).
- **token-billing:** backend F1+F2 completo y probado (suite + batería manual). Quedan: entrega real de productos Play/Stripe (necesita Play Console + precios finales tuyos), streaming del proxy, UI de apps (paywall/engine "hosted-proxy") y dashboards.
- Limpieza: sesiones y admins de prueba revocados; stub provider y proxy HTTP eliminados. La cuenta `bw-billing@palique.example` queda en la BD local como dato de prueba.

## 4. Veredicto

**Batería Fase 2+3 superada: 16/16 pruebas** (9 en emulador + 7 en navegador/API), con el flujo de retención (lección → siembra → cola → repaso → notebook) funcionando de punta a punta en el emulador y el camino de dinero (unlock → proxy → metering → agotamiento) cubierto de extremo a extremo en el backend. Cero crashes. Los pendientes son los ya documentados en los changes (iOS UI, adaptadores de compra reales, streaming).
