# Informe — Progreso multi-idioma

**Fecha:** 2026-09-28 · **SDD:** `openspec/changes/multilanguage-progress/` · Commits: `d512fcc` (core), `500547c` (api), `5796d56` (UI), `023b83b` (fix emulador)

## La pregunta y la respuesta

**¿Mover temarios, ejercicios y progreso a una base de datos organizada por idioma? No** — es un problema de *acotado* (scoping), no de almacenamiento:

- **Contenido**: el pipeline JSON canónico → código generado ya es multi-idioma (N archivos, validación contra el registro de 8 idiomas, `forLanguage`). Una BD rompería offline-first, backups y CI de drift sin beneficio a esta escala. BD de contenido sí tendrá sentido cuando el panel admin edite contenido (futuro, con caché en app).
- **Progreso local**: el archive ya estaba keyeado en su mayoría (ids prefijados, sesiones con idioma, palabras con clave trilingüe). Añadir `languageID` opcional a los dos structs que faltaba (`CompletionMark`, `ExpressionState`) completa el keying con el patrón sin-migración ya probado. Room/SwiftData: reescritura de dos stacks por cero ganancia.
- **Servidor: SÍ** — `user_progress` gana columna `language` (032): sin ella, ids compartidos entre idiomas (los 24 temas, "free") colisionaban y el cap de 200 desalojaba entre idiomas.

## Qué cambió

- **Local**: marcas y expresiones llevan idioma; el cuaderno de repaso keyea por (idioma, texto) — ya no chocan "soup" en dos idiomas; `ProgressEngine` calcula racha, gráfico de 30 días, temas, repaso, palabras, ejercicios y listening **del idioma activo**; las estadísticas de voz filtran sesiones; los niveles ya eran por curso (= idioma).
- **Legacy**: marcas sin idioma (2 días de dev) cuentan como actividad global en rachas/gráficos pero no como "temas practicados" de nadie; el texto repetido en el cuaderno es adoptado por el idioma activo.
- **Servidor**: PK `(account, kind, item, language)` con allowlist cerrada (8 idiomas + legacy), merge monótono por idioma, wire con `language` requerido, GET y browse del panel lo devuelven.
- **UI**: cambiador rápido de idioma (pills con nombre nativo) en Aprender en ambas plataformas — visible también cuando un idioma no tiene temario (fallo encontrado en emulador y corregido); hoja de progreso 100% por idioma con nuevo copy de alcance; historial Android filtrado por idioma.

## Verificación (real, esta sesión)

- **Android**: `testDebugUnitTest` **453/453** + assembleDebug.
- **Backend**: `npm run check` + `npm test` **417/417** (postgres:17 fresco): idioma desconocido/ausente rechazado, mismo item en dos idiomas = dos filas, aislamiento del merge por idioma.
- **Paridad/scripts**: `check_cross_platform`, ambos exportadores `--check`, unittest 66+ OK; cadenas 343/343 + plurales.
- **Emulador** (capturas 24–27): switch en caliente Español ↔ English; español = estado vacío con cambiador (atascos imposibles); inglés = nivel, estrellas, %, recall intactos. Cero crashes.

## Pendientes

- Gate macOS (core iOS espejado) y pasada en Pixel con historial real.
- Contenido de segundo idioma: pipeline listo; dime cuál idioma y se autoramiza como proyecto de contenido.
- Telemetría sin dimensión de idioma (deliberado: catálogo content-free; `courseID` ya lo lleva).
