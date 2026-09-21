# Informe de rebranding — 2026-09-21

## Contexto

El proyecto es un fork de `Chuloo/mural` (Hackmamba / William Imoh). La marca real del producto en el código es **"Mural"** — no "Looro": el nombre *looro* solo existe en el directorio local y en el fork remoto `jkdc/looro`; en el código aparece 0 veces. Para publicar en App Store / Play Store hay que eliminar la marca del upstream (nombre, bundle IDs, paquete, emails, dominios, metadatos).

Alcance medido: **~2.850 apariciones de "mural" en ~430 ficheros**, más el proyecto Xcode generado (`Mural.xcodeproj`, regenerable con `scripts/generate_project.py`).

## Inventario por capas

### 1. Identidad de build (bloqueante para publicar)

| Plataforma | Actual | Dónde |
| --- | --- | --- |
| iOS bundle ID | `no.william.mural` (+ `.uitests`) | `scripts/generate_project.py` (el proyecto se regenera) |
| iOS target/proyecto | `Mural`, `Mural.app`, `MuralUITests`, `Mural.xcodeproj` | `scripts/generate_project.py` |
| iOS paquete Swift | `MuralCore` (library + target + tests) | `apps/ios/Package.swift`, `scripts/generate_project.py` |
| iOS display name | `Mural` | `apps/ios/App/Info.plist` (`CFBundleDisplayName`) |
| iOS variables de configuración | `MURAL_MANAGED_*`, `MURAL_GOOGLE_*`, `MURAL_APPLE_*` y callback scheme `no.william.mural.accounts-disabled` | `Info.plist`, `apps/ios/Config/*.xcconfig` |
| iOS fichero app | `MuralApp.swift` | `apps/ios/App/` |
| Android namespace | `chat.mural` (+ `chat.mural.engines.local`) | `app/build.gradle.kts`, `core/engine-local/build.gradle.kts` |
| Android applicationId | `chat.mural.android` (+ sufijo `.uitest`) | `app/build.gradle.kts` |
| Android proyecto Gradle | `rootProject.name = "Mural"` | `apps/android/settings.gradle.kts` |
| Android árbol de fuentes | `java/chat/mural/**` (renombre de directorios + package/imports) | `app/src/main`, `app/src/test`, `app/src/androidTest` |
| Android clases con marca | `MuralViewModel`, `MuralIcons` | y referencias en tests |
| Android recursos | `android:label="Mural"`, `@mipmap/ic_mural`, `Theme.Mural` | `AndroidManifest.xml`, `res/` |

### 2. Cadenas visibles al usuario

- Android: 19 entradas en `res/values/strings.xml` ("Mural couldn't connect…", "Mural %1$s · Android", etc.) + strings hardcodeadas en Compose (ui/*.kt).
- iOS: ~35 textos con "Mural" en `App/*.swift` (onboarding, permiso de micro, librería…).
- La paridad de strings (252, `scripts/check_cross_platform.py`) debe seguir cuadrando tras el renombrado.

### 3. Backend (`services/api`)

- Nombre de paquete npm: `mural-server`.
- Base de datos: usuario/db `mural`, `mural_test`, `mural_billing_test`; rol `mural_runtime`; `application_name: 'mural-billing'`.
- Locks y dominios internos: `hashtext('mural-migrations')`, `mural-welcome-minutes`, `mural-hosted-funding-cap`, HMAC `mural-play-v1`, `mural-minute-receipt-v1`, `mural-ai-report`, schema `mural_result`, cabeceras `x-mural-proxy-token` / `x-mural-client-ip`, origen permitido `https://mural.chat`, proyecto compose `mural-foundation-smoke`.
- No hay datos en producción desde este fork → renombrado limpio, sin migraciones ni compatibilidad hacia atrás.

### 4. Email de soporte (bloqueante para Play)

`hi@hackmamba.io` está **hardcodeado en la UI** de Android (flujo de borrado de cuenta que Google exige):
`AccountDeletionSupportDialog.kt` (`DELETION_SUPPORT_EMAIL`, mailto con asunto "Mural account deletion request"), `ReportConversationSheet.kt`, y su test `AccountSheetTest.kt`. También en `SECURITY.md` y docs del backend. Hay que sustituirlo por el email del publicante.

### 5. CI y metadatos de publicación

- Workflows: artefactos `mural-android-debug`, `mural-android-unsigned-bundle`; Postgres CI `mural_test`/`mural_billing_test` (`.github/workflows/*`, `.github/pull_request_template.md`).
- `release/app-store-metadata.md`, `release/app-privacy.md`, `release/README.md`: redactados para la entidad upstream (Hackmamba Inc., `mural.chat`, OAuth `mural-508413`). Para publicar, el fork necesita **sus propios** datos (entidad legal, URLs, email de contacto). Renombro la marca y dejo `[REQUIRED: …]` donde haga falta dato del publicante.
- El fork no puede reutilizar clientes OAuth ni backend hosted del upstream → cuentas gestionadas siguen **deshabilitadas** por defecto (ya están en `NO`); publicación en modo BYOK (clave propia).

### 6. Históricos (propuesta: no tocar)

`verification/*.md`, `release/android/evidence/*.json` (algunos con decenas de apariciones), `openspec/changes/*` cerrados, informes `informe-*.md` de raíz y `LICENSE`. Son registros fechados; reescribirlos falsificaría evidencia y rompería enlaces a issues/PRs del upstream. `LICENSE` (MIT) además **exige conservar** el aviso de copyright de Hackmamba; se puede añadir línea propia debajo.

### 7. Assets que el sed no arregla

- **Icono de app**: `@mipmap/ic_mural` + catálogo `AppIcon` + `scripts/make_icon.swift`. Hace falta un logo nuevo (puedo dejar un icono provisional con la letra del nuevo nombre).
- **Capturas de pantalla**: `marketing/screenshots/**` (y las referenciadas en README) muestran "Mural" en la UI → re-capturar tras el renombrado (requiere simulador iOS/macOS — gate ya pendiente en tu flujo).

## Plan propuesto (SDD por fases)

1. **T1 — Mapa de renombrado + identidad iOS**: tabla de sustituciones (nombre, bundle, paquetes, prefijos de env), renombrar generador/Package.swift/Info.plist/xcconfig, regenerar `*.xcodeproj`, tests offline de MuralCore (macOS queda como gate pendiente conocido).
2. **T2 — Identidad Android**: namespace/applicationId/directorios `chat/mural` → nuevo, recursos (`ic_mural`, `Theme`), clases; `:app:testDebugUnitTest` en verde (baseline 394/394) + `lintDebug`.
3. **T3 — Backend + CI**: paquete npm, DBs, locks/HMAC/cabeceras/schema, compose, workflows; suite del backend en docker en verde.
4. **T4 — Cadenas visibles con paridad**: strings.xml + Kotlin + Swift; `check_cross_platform.py` en verde con la paridad intacta.
5. **T5 — Soporte + docs vivos + metadatos**: email del publicante centralizado, README/docs vivos, `release/*.md` con `[REQUIRED]` para datos legales, SECURITY/CONTRIBUTING.
6. **T6 — Verificación cruzada**: grep de "mural" = 0 en código/config/doc viva (solo quedan históricos, LICENSE y third-party), todas las suites en verde, informe final con veredicto honesto.

Pendientes post-rebranding (fuera de alcance del sed): logo nuevo, re-captura de screenshots (macOS), datos legales de store, alta de clientes OAuth propios si algún día se activan cuentas.

## Decisiones que necesito

1. **Nombre nuevo** — propongo **Looro** (coincide con tu fork `jkdc/looro` y el directorio local). Vale cualquier otro.
2. **Identificadores inversos** — propuesta por defecto: iOS bundle `com.jkdc.looro` y Android `applicationId` `com.jkdc.looro.android` (namespace `com.jkdc.looro`), o el dominio que tengas.
3. **Email de soporte** para la UI de borrado de cuenta (obligatorio en Play; puede ir como constante centralizada con TODO si aún no quieres publicarlo).
4. **Históricos** — ¿los dejamos intactos (recomendado) o rebranding absoluto?
