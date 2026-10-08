# Informe: tests de verificación de nivel (¿estoy listo para superar mi nivel?) — 2026-10-08

Análisis para un **test de superación de nivel** ejecutable cuando el usuario
quiera, con duración de 10–15 minutos, **rotativo** (nunca siempre el mismo),
eficiente, y con un camino claro hacia el backend semanal. Rama
`feature/learning-platform`; se apoya en el placement recién implementado
(`openspec/changes/placement-test/`) y en los checkpoints existentes.

## 1. Objetivo y diferenciación con lo que ya hay

| Feature | Pregunta que responde | Duración | Cuándo |
|---|---|---|---|
| **Placement** (ya en repo) | ¿En qué nivel empiezo? | ~4 min | Libre |
| **Checkpoint** (ya en repo) | ¿He preparado este nivel? (señal de preparación, 16 ítems fijos, gating por readiness) | ~5 min | Cuando el nivel está intentado |
| **Este informe: verificación de nivel** | ¿Estoy listo para **superar** mi nivel actual y pasar al siguiente? | 10–15 min | Libre, repetible, **rotativo** |

El nuevo test no reemplaza al checkpoint: lo **absorbe y degrada
gracefulmente** (ver §8). La diferencia clave con el placement: aquí se
evalúa **dominio del nivel completo** (con umbrales por categoría), no una
estimación de banda.

## 2. Inventario de material reutilizable (verificado hoy)

- **Ejercicios de lecciones con nivel**: `shared/courses/*-foundations.json`
  — EN: **364 ejercicios** ya etiquetados por nivel (A1: 160, A2: 72, B1: 80,
  B2: 32, C1: 20) y por tipo (choice 92 / gap 91 / order 91 / written 90).
  ES: 316. Cada uno trae `explain` (docencia gratuita en la revisión final).
- **Preguntas de listening con nivel**: `shared/listening/*.json` — EN:
  **282 preguntas** (ES: 269) sobre textos con `level` explícito.
- **Checkpoints**: 16 preguntas por nivel y lengua
  (`shared/assessments/*-checkpoints.json`) con umbrales overall + categorías.
- **Motores y patrones recién construidos para placement**: `ExerciseEngine`
  (corrección determinista), `PlacementScreen` (fases, feedback, sesión
  resumible con `rememberSaveable`), `PlacementResultStore`, exporter +
  validación + `--check` en CI, ledger `assessment` aceptado por el server.
- **LevelBar por niveles** en Aprender: el hogar natural de la entrada.

**Consecuencia**: el banco del test nuevo se **cosecha** del contenido ya
autorizado y validado (validez de contenido: se evalúa lo que se enseñó, y
cada ítem sabe de qué unidad viene → el resultado puede recomendar *qué
repasar*), en vez de redactar desde cero. Solo se autoran los huecos.

## 3. Cómo lograr "que cambie cada X" — análisis de opciones

**Opción A — Banco local + rotación semanal determinista.** Se empaqueta un
banco por nivel (≥ 2× el tamaño del test) y el test de la semana se compone
con una semilla determinista: `seed = idioma + nivel + semana ISO + versión
del banco`. Misma prueba para todos los usuarios esa semana; cambia cada
lunes; dos semanas consecutivas no comparten ítems (barajado por bloques).
Sin red, sin cuentas, offline-first, verificable en JVM, idéntico en
iOS/Android (misma semilla, mismo banco generado).

**Opción B — Endpoint semanal en el backend.** `GET /v1/level-tests/current`
devuelve el test de la semana. Ventajas: rotación sin actualizar la app,
curaduría manual (una semana "temática"), telemetría de ítems, A/B. Costes:
el repo **no tiene hoy ningún canal de contenido por servidor** (todo el
contenido es export→bundled con checkers); añadirlo es un cambio de
arquitectura que merece su propio cambio; exige red + despliegue hosted
activo (hoy apagado); y la calidad no mejora por sí sola.

**Opción C — Híbrido (recomendada).** v1 = Opción A con una propiedad
clave: **el JSON del test semanal compuesto es exactamente el contrato que
el BE servirá en v2**. El cliente consume "un banco" venga de
`LevelTestsData.kt` (bundled) o de la respuesta del endpoint (cacheada con
fallback offline al banco local). Así el backend entra cuando aporte valor
real (curaduría, rotación sin release, analítica) sin reescribir nada del
cliente.

**Sobre tu propuesta** (v1 local con un único test + llamada BE semanal
después): el único test fijo contradice tu propio requisito de variedad
hasta que el BE exista, y el BE es hoy el camino largo. Con la cosecha de
§2, tener 2 variantes o 20 cuesta casi lo mismo; por eso la recomendación es
banco local + rotación semanal desde el día uno, con el endpoint v2
especificado de serie.

## 4. Diseño del test (10–15 min, eficiente)

- **~28 ítems en 5 secciones**, de menos a más esfuerzo (eficiencia
  percibida: se arranca rápido, se termina comprometido):
  1. Calentamiento de vocabulario — 4 choice (~1,5 min)
  2. Gramática del nivel — 8 choice/gap/order (~3 min)
  3. Lectura — 2 micro-textos × 3 preguntas (~3 min)
  4. Comprensión auditiva — 4 ítems con TTS del idioma (~2,5 min)
  5. Producción escrita — 3 written cortos (~2,5 min)
  (v1.5 opcional: 2 ítems de pronunciación con `PronunciationEngine` — el
  diferencial de Palique; queda fuera de v1 por variabilidad de micrófono.)
- **Sin feedback ni puntuación durante el test** (condiciones de examen);
  **revisión final con cada explicación** — sigue enseñando sin romper el
  ritmo. **Sin temporizador** (ansiedad cero, filosofía de la app); la
  estructura por secciones ya marca el ritmo.
- **Corrección**: `CheckpointEngine.grade` con umbrales overall + suelos por
  categoría (grammar/vocab/reading/listening/writing) del propio checkpoint
  del nivel — la banda de "preparado" ya está calibrada ahí.
- **Resultado**: "Preparado para superar B1 ✅" o "Casi — refuerza X", con
  desglose por categoría y, **como cada ítem sabe de qué unidad procede, una
  lista concreta de lecciones a repasar** (el mapa ítem→unidad lo aporta la
  cosecha). Nada se desbloquea ni se bloquea: señal, no puerta.
- **Sesión resumible** (patrón de `PlacementScreen`): ids preguntados +
  respuestas en `rememberSaveable`; interrumpir a los 7 minutos no cuesta el
  test. Re-entrada siempre desde cero al cerrar.

## 5. Rotación determinista (el "cada X")

- Semana ISO como reloj (lunes 00:00 UTC); `seed = sha256(lang|level|isoweek|version)`.
- Selección: barajado determinista (PRNG sembrado) del banco del nivel,
  partición en bloques del tamaño del test, **índice = weekIndex módoo
  número de bloques** con salto para no repetir bloque consecutivo; dentro
  del bloque se reordenan las secciones respetando la estructura de §4 y el
  balanceo por categoría.
- Propiedad comprobable en JVM: dos semanas consecutivas no comparten más
  de X% de ítems (con banco ≥ 2× test, solape ≈ 0); 4 semanas consecutivas
  no repiten test completo.
- Con banco cosechado de ~50–60 ítems/nivel (EN lo permite hoy; ver §9 para
  C1), el ciclo de variedades dura meses.

## 6. Pipeline de contenido (extensión del de placement)

1. `scripts/build_leveltests.py` (nuevo): cosecha ejercicios de lección +
   preguntas de checkpoint + preguntas de listening (todas con `level`),
   dedupe por id/prompts, balancea categoría/tipo por sección, **registra
   `unitID` de procedencia** de cada ítem, y emite
   `shared/leveltests/<lang>-leveltests.json` (schema propio versionado).
2. `scripts/export_leveltests.py` (nuevo, clon del de placement): valida
   (mínimos por sección/nivel, coherencia kinds, skills conocidas, unidad
   existente) y genera `LevelTestsData.kt`; `--check` añadido a
   `contracts.yml`.
3. Authoring puntual de huecos (p. ej. written de B2/C1 si la cosecha no
   cubre el mínimo) — se marca como puerta de revisión en el verify-report,
   igual que los bancos de placement.

## 7. Persistencia e integración (cero cambios de schema)

- Ledger: marca `assessment` con `itemID = levelcheck-<lang>-<level>`
  (slug-válido) y `bestScore` = overall → sync y borrado GDPR gratis.
- Local: `LevelTestHistoryStore` (mismo patrón que `PlacementResultStore`)
  con los últimos intentos (semana, overall, categorías, unidades débiles)
  para la tarjeta de entrada y la tendencia ("+B1 72% → 84%").
- UI: tarjeta dentro del nivel visible de Aprender ("Verifica tu B1 · test
  de esta semana · último: 72%") + pantalla `LevelTestScreen` que reutiliza
  los componentes de `PlacementScreen` en modo examen (secciones, sin
  feedback, revisión final). El checkpoint actual queda como está hasta que
  este lo sustituya (§8).

## 8. v2 — Backend semanal (especificado ahora, construido cuando aporte)

- `GET /v1/level-tests/current?language=es&level=B1` → mismo JSON que
  compone el cliente hoy (banco de la semana ya montado) + `weekId`,
  `expiresAt` (lunes siguiente), `version`. Caché con ETag; el cliente
  guarda el último conocido y cae al banco bundled si no hay red.
- El BE puede derivar el test con **la misma semilla determinista** sobre el
  banco versionado (cero tablas) o curarlo (tabla `level_test_weeks` + CLI
  estilo `minutes-admin`) cuando se quiera una semana temática.
- Encima del hosted existente (accounts admission, limiter) — nada de
  comercio obligatorio. Se activa cuando haya despliegue real y ganas de
  curar semanas; hasta entonces el cliente ni lo llama.

## 9. Plan de fases

| Fase | Contenido | Verificación |
|---|---|---|
| **F0 — Cosecha y bancos** | `build_leveltests.py` + `export_leveltests.py`; bancos EN+ES (A1–B2 con cosecha; **C1 se lanza si alcanza mínimo, si no queda "próximamente" sin mostrar entrada**) | Validaciones del exporter; `--check` en CI; revisión de huecos authorados |
| **F1 — Rotación** | `LevelTestEngine.kt`: composición semanal determinista, estructura por secciones, no-solape semanal | Tests JVM: determinismo, no-repetición consecutiva, solape ≤ umbral, estructura/balanceo estables ante semanas arbitrarias |
| **F2 — Examen + resultado** | `LevelTestScreen` (modo examen, revisión final, desglose, recomendación de unidades) + `LevelTestHistoryStore` + tarjeta en Aprender + strings EN/ES | Tests JVM de estado; smoke en emulador del ciclo completo (incl. interrupción/reanudación) |
| **F3 — Pronunciación (opcional)** | 2 ítems con `PronunciationEngine` en la sección final | Prueba en dispositivo real (micrófono/TTS varían) |
| **F4 — Backend v2** | Endpoint semanal + caché + fallback + (opcional) CLI de curaduría | Suite server (PG efímero) + contrato en `shared/contracts/`; cliente conmuta bundled↔BE sin cambios de UI |

## 10. Riesgos y mitigaciones

- **Memoria entre semanas**: banco ≥ 2× test + no-solape consecutivo
  garantizado por diseño (test JVM que lo clava).
- **C1 escaso en la cosecha** (EN: 20 ejercicios): authoring puntual o no
  lanzar C1 en v1 — la entrada solo aparece donde hay banco válido.
- **Validez del corte "preparado"**: reutilizar los umbrales de los
  checkpoints existentes (ya razonados) y no inventar otros nuevos.
- **TTS variable por dispositivo** (listening): botón de repetir, ya
  implementado en placement.
- **Contenido cosechado desalineado del temario vivo**: el exporter valida
  `unitID` contra el catálogo actual — si una lección desaparece, el build
  falla antes que servir un ítem huérfano.

## 11. Decisiones — cerradas el 2026-10-08 (Joaquín)

1. **Ventana de rotación: semanal** (la propuesta original del informe; el
   test cambia cada lunes por semilla determinista).
2. **Alcance de idiomas: el mismo que el placement v1 (EN + ES).** El resto
   de idiomas cuando sus bancos existan.
3. **Listening: incluido ya en v1** (bloque con TTS del idioma).
4. **Un test distinto por nivel** (A1…): la entrada vive dentro de cada
   nivel de la barra CEFR y la evaluación se muestra al terminar cada uno.
5. **Resultado con sugerencia direccional**: superado → sugiere estar listo
   para el siguiente nivel (con acción de salto, nunca fija); no superado →
   sugiere qué reforzar (categorías débiles + unidades concretas).
6. **Los resultados se muestran siempre**: la tarjeta del nivel mantiene el
   último resultado (fecha, nota, desglose) visible.
7. **Idioma de las instrucciones**: el ajuste "Instructions language" ya
   existente (el del placement).
8. **Pronunciación**: fuera de v1 (F3 del informe original, cuando haya
   calibración de dispositivo).
9. **Checkpoint antiguo**: su entrada se sustituye por este test por nivel
   en cuanto el cambio esté verde (un solo "test de nivel" por concepto).

*(El C1 se lanza solo donde la cosecha alcance los mínimos por sección; si
no, el nivel no muestra entrada — sin promesas vacías.)*
