# Verify Report: app-language-selector

Fecha: 2026-11-03 · Host: Linux (sin macOS/Xcode, sin toolchain Swift)

## Qué se ejecutó y pasó

| Verificación | Resultado |
|---|---|
| Compilación Android (`:app:compileDebugKotlin`) con `AppCompatActivity` + appcompat 1.7.1 | ✅ |
| Tests unitarios JVM Android (`:app:testDebugUnitTest`) — 870 tests, incl. los 5 nuevos de `AppLanguageTest` | ✅ 0 fallos |
| Tests instrumentados `SettingsDetailsTest` en emulador API 36 — 3/3 verdes: paridad de recursos es de las etiquetas del proveedor, detalle completo de Settings, y flujo del selector (`settings-app-language` → Español → recreación de la actividad → UI en español, incl. botones del proveedor) | ✅ |
| `:app:lintDebug` sobre el árbol final | ✅ (una reindentación del lambda del selector para satisfacer `SuspiciousIndentation`; verificado que el fichero base pasaba y el cambio del selector lo introducía) |
| `make check` (exporters `--check`, cross-platform, db schema) | ✅ sin drift |
| Catálogo iOS: JSON válido, 414 claves, cobertura `es` = 100 %, estados todos `translated` (gate Python, espejo de `LocalizationCatalogTests`) | ✅ |
| XML Android bien formados (manifest, locales_config, strings EN/ES, styles) | ✅ |
| Auditoría de literales: re-extracción de los 14 ficheros iOS → toda clave de vista cubierta o excluida explícitamente (marca y badge DEBUG); destapó y corrigió el desajuste `Level %lld · %@` (LevelStats.level es Int) | ✅ |
| Regeneración `project.pbxproj` (`generate_project.py`) — ahora emite App target + AppUITests + **AppTests** (TEST_HOST, LearningCore) + variante `InfoPlist.strings` en/es + `Localizable.xcstrings` + `knownRegions` en/es/Base | ✅ diff revisado |

## Qué NO se pudo verificar en este host (requiere macOS / toolchain)

- **Compilación del App target iOS** (`make ios-build`) y **AppTests** incl.
  `LocalizationCatalogTests` — Xcode 26 no existe en Linux. El gate
  equivalente (JSON + cobertura `es`) sí se ejecutó con Python.
- **`swift test --package-path apps/ios`** (Core) — no hay toolchain Swift
  instalado en este host. Core no se tocó, pero no se confirmó verde aquí.
- **`AppUITests`** — actualizado para fijar `-AppleLanguages (en)`; solo
  ejecutable en macOS.
- **Riesgo residual de tipos Swift**: las claves con interpolación se
  derivaron del tipo de cada expresión (String→`%@`, Int/Int64→`%lld`),
  verificadas contra las definiciones de Core. Si `make ios-build` falla por
  una clave con formato, el mensaje de Xcode indicará la clave exacta.

## Pruebas test-first añadidas

- `AppLanguageTest` (JVM) — fallaba: la clase no existía.
- `SettingsDetailsTest.providerButtonsRenderLocalizedLabels` (instrumentado)
  — fallaba: los literales no se traducen bajo locale `es`.
- `SettingsDetailsTest.appLanguageSelectorSwitchesSettingsLanguage`
  (instrumentado) — fallaba: no existía el selector.
- `LocalizationCatalogTests` (App target, macOS) — fallaba: no existía el
  catálogo; espejo Python ejecutado en Linux.

## Pendiente para revisión humana

- Las ~410 traducciones ES de iOS salen de un único pase automatizado (LLM);
  recomendado un repaso nativo antes de publicar, en particular los textos
  legales (consentimiento IA, privacidad, tokens) y los tonos de Settings.
- Confirmar en dispositivo el comportamiento del selector bajo "Sistema"
  cuando el idioma del dispositivo no es ni en ni es.
