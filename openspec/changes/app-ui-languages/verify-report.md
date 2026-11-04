# Verify Report: app-ui-languages

Fecha: 2026-11-04 · Host: Linux (emulador API 36; sin macOS/Xcode ni toolchain Swift)

## Fases entregadas (un commit por idioma)

| Fase | Idioma | Commit |
|---|---|---|
| F0 | Infra: selector 9 opciones, `locales_config`, `UiLanguage`, gates, generador lproj | `eb20b95` + `da21e6a` |
| 1 | Francés | `dc06744` |
| 2 | Alemán | `74b1fc4` |
| 3 | Italiano | `9380e48` |
| 4 | Portugués (BR) | `ae178c1` |
| 5 | Noruego Bokmål | `031b239` |
| 6 | Chino simplificado | `04f33cf` |

Volumen por idioma: Android 727 entradas (8 ficheros de valores) + iOS 414
claves del catálogo + `InfoPlist.strings` de permisos.

## Qué se ejecutó y pasó

| Verificación | Resultado |
|---|---|
| `scripts/check_ui_strings.py` (gate completo: paridad de claves Android por idioma, cobertura iOS por clave e idioma, integridad de especificadores de formato) | ✅ 8 idiomas |
| `make check` completo (unittest de scripts + exporters + cross-platform + db schema + gate de UI strings) | ✅ |
| `:app:processDebugResources` tras cada fase (aapt valida los recursos por locale) | ✅ |
| Tests instrumentados `SettingsDetailsTest` en emulador API 36 — 4/4: recursos localizados de las etiquetas del proveedor, detalle completo de Settings, flujo completo del selector a Español (con recreación), y verificación determinista de recursos + modelo de selector para los 6 idiomas nuevos (Terminé/Fertig/Fatto/Concluído/Ferdig/完成) | ✅ |
| Regeneración `project.pbxproj` con los 8 `.lproj` en la variante InfoPlist | ✅ |

## Notas de la verificación instrumentada

- El intento de conducir el flujo de click completo para un idioma nuevo
  (dropdown → item → recreación) colgó la sincronización idle de Compose en
  dos ejecuciones (carrera conocida entre `DropdownMenu` en ventana propia y
  la recreación de actividad); el flujo completo ya queda cubierto por el
  test de Español, y los idiomas nuevos se verifican de forma determinista.
- Un "Process crashed" puntual en la instrumentación se resolvió con un
  arranque en frío del emulador (estado degradado tras horas encendido), no
  por el código: la misma suite pasó 4/4 después.

## Qué NO se pudo verificar en este host

- Compilación del App target iOS (`make ios-build`), `AppTests` y
  `AppUITests` — requieren macOS/Xcode 26. El gate equivalente del catálogo
  sí corre en Linux (`check_ui_strings.py`).
- `swift test --package-path apps/ios` (Core) — sin toolchain Swift en este
  host; Core no se modificó en este change.

## Pendiente para revisión humana (obligatorio antes de publicar)

Las ~6.600 traducciones nuevas (727 × 6 en Android + 414 × 6 en iOS) salen
de pases automatizados por idioma. Los agentes de traducción marcaron
decisiones que conviene repasar con hablantes nativos:

- **fr**: acuerdos de género por defecto (masculino genérico), tipografía
  aplicada (guillemets, espacio antes de ? : !), « Founda­tions » como marca.
- **de**: "Streak" como préstamo, "Abruf" para recall, comillas „ “.
- **it**: "vocabolo" para evitar rupturas de concordancia, "Minuti inclusi".
- **pt**: género asumido desde el referencial español, "Build pessoal".
- **nb**: "rekke" (streak), "hukommelsesbarer", acuerdo de género en
  recall states, reformulación del plural invariante "bruk".
- **zh**: términos de producto (Pro→专业版, tokens→代币, hosted→托管), YOU→我,
  tab names (聊聊/话题/词语/学习/听力).
- Comunes: los textos legales (consentimiento IA, privacidad, tokens) y los
  avisos de permisos de `InfoPlist.strings` (autorizados a mano por idioma).
