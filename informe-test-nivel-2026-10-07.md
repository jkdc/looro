# Informe: test de nivel (placement test) para Palique — 2026-10-07

*(Revisado y corregido el 2026-10-07 tras auditoría de sus claims contra el
código: formato de itemID corregido al SLUG del server, contradicción
listening/F3 unificada, sobreestimación de checkers ajustada, banco ampliado a
8–10/banda y criterio de estabilidad realista. Veredicto de la auditoría al
final, §8. **Decisiones cerradas el 2026-10-08 — ver §7; el informe es
implementable tal cual.**)*

Propuesta de diseño para un **test de nivel CEFR libre y opcional** que el
usuario pueda hacer cuando quiera, para que la pestaña Aprender arranque en el
nivel correcto. Primera iteración: este informe describe el cómo; tras
validarlo se implementa. Análisis sobre `feature/learning-platform`, con la
pantalla Aprender ya reorganizada por niveles (ver
`docs/pantalla-aprender-diseno.md`).

## 1. Principios de diseño

1. **Sencillo**: el núcleo debe caber en 3–5 minutos. Los tests largos no se
   terminan (el EF SET completo dura 50 min y su versión "quick" de 15 existe
   precisamente por eso).
2. **Libre y repetible**: sin puertas, sin cooldown, desde Aprender o Ajustes,
   tantas veces como se quiera. **El resultado nunca bloquea contenido** — hoy
   todo el catálogo ya es accesible.
3. **El test sugiere, el usuario decide**: tras el resultado siempre puede
   cambiarse el nivel manualmente (la barra CEFR ya lo permite). Un placement
   erróneo no debe poder encerrar a nadie.
4. **Honesto sobre lo que mide**: un test de 4 minutos estima gramática y
   vocabulario con precisión razonable; escuchar, leer, hablar y escribir solo
   se estiman de forma aproximada. La UI lo dirá ("estimación", no
   "certificado"), igual que las barras de recuerdo ya evitan prometer
   calibración psicométrica.

## 2. Cómo lo hacen las plataformas de referencia

| Plataforma | Modelo | Duración | Qué medimos de útil |
|---|---|---|---|
| **Duolingo** (in-app) | **Adaptativo por ramificación**: cada respuesta re-estima el nivel y la siguiente pregunta sube o baja de dificultad; añadió *partial credit* en 2018 | ~10–20 ítems, "un par de minutos" | El modelo exacto que nos encaja: corto, ramificado, termina por confianza, salta directo a una unidad CEFR |
| **Linguaskill** (Cambridge) | Adaptativo **IRT** por módulos (reading+listening) | ~15–30 min/módulo | Alineación CEFR formal; usa IRT porque tiene miles de resultados para calibrar |
| **EF SET** | Adaptativo IRT, escala 0–100 → CEFR | 50 min (cert.) / 15 min (quick) | El reporting: puntuación + equivalencia CEFR + desglose por destreza |
| **Busuu** | Cursos etiquetados por CEFR + test de nivel por destreza al entrar | ~5 min/nivel | La UX: test antes de elegir nivel, resultado editable |

**Decisión metodológica: ramificación (branching), no IRT.** IRT (curvas de
respuesta al ítem, calibración con cientos de sujetos por ítem) es lo correcto
para tests certificados con grandes poblaciones; con un banco pequeño y una
app personal, un ladder de ramificación por bandas CEFR es más simple, 100%
determinista y testeable en JVM, y consigue la misma precisión *de banda*
(convergencia en 12–20 ítems). Es la misma filosofía del resto de la app:
heurísticas de producto honestas, no pseudociencia (así lo declaran las barras
de recuerdo).

## 3. Qué cubre el test ("todos los ámbitos") y de qué forma

El catálogo CEFR distingue 8 categorías de skill (`shared/skills/skill-catalog.json`):
`grammar, vocab, listening, reading, speaking, writing, pron, function`. Un
test escrito interactivo mide bien dos, aceptablemente otras dos, y mal las
restantes. Propuesta por fases:

| Ámbito | v1 | Fase posterior | Mecanismo |
|---|---|---|---|
| Grammar | ✅ núcleo adaptativo | — | choice/gap (motores ya existentes) |
| Vocab | ✅ núcleo adaptativo | — | choice/gap |
| Reading | ✅ núcleo (ítems con micro-texto) | — | choice sobre 1–2 frases/párrafo corto |
| Listening | ✅ v1 (bloque final fijo, TTS) — decidido 2026-10-08 | audio grabado real | TTS del sistema lee el estímulo (la app ya habla), MC de comprensión. Se construye como F3, tras el núcleo escrito |
| Writing | ✅ v1 (decidido 2026-10-08): producción corta determinista | composición libre | 3–4 ítems "escribe esto en el idioma" desde el significado, corregidos con el matching exacto del `ExerciseEngine` (kind `written` ya existe y es testeable). **No** es redacción libre: eso exige evaluación semántica y va a v2 (conversación de calibración). Mide producción activa, el complemento natural del reconocimiento que puntúa el resto |
| Speaking | ❌ fuera de v1 | v2 | Muestreo por conversación: una "conversación de calibración" guiada evaluada con el motor de enseñanza (el punto fuerte de Palique; además el uso real ya genera evidencia adaptativa) |
| Pron | ❌ | v2 | ya existe `PronunciationEngine` |

Además del test, dos complementos que las plataformas validan:

- **Autoevaluación CEFR** (la "can-do grid" del Council of Europe): 5
  afirmaciones por nivel ("Puedo presentarme y hablar de mi trabajo"), una
  marca por nivel. 30 segundos, sin idioma de examen, y sirve para
  contrastar: si el test y la autoevaluación discrepan mucho, la UI pide
  confirmación en lugar de confiar en uno solo.
- **Evidencia de uso** (v2): las conversaciones y repasos reales ya producen
  señales continuas; a futuro, "tu nivel estimado subió a B1" puede llegar del
  uso, no solo del test.

## 4. Diseño técnico propuesto (v1)

### 4.1 Banco de ítems — reutilizar el pipeline de contenido

- Nuevo fichero por idioma: `shared/placement/<lang>-placement.json`, mismo
  espíritu que `shared/assessments/*-checkpoints.json`. Cada ítem es un
  `Exercise` existente (`choice`/`gap`) más tres campos: `level` (A1..C1),
  `skill` (id del catálogo, de donde salen las categorías) y `weight`
  (difficulty fina 1–3 dentro de banda, para el ladder).
- Exportado por un `scripts/export_placement.py` (clonando
  `export_assessments.py`) hacia `PlacementData.kt`. **Nota honesta**: los
  checkers actuales (`check_db_schema`, `check_cross_platform`) no validan un
  JSON nuevo — la validación (opciones sin duplicados, única respuesta
  correcta, bandas completas, mínimo por categoría y banda) la hace el propio
  exporter al estilo de `export_courses.py`, y `check_cross_platform`
  verifica la paridad de la fixture generada.
- **Tamaño del banco v1** (decisiones 2026-10-08: EN+ES, con listening y
  writing): núcleo **8–10 ítems × 5 bandas** + **3 de listening y 3 de
  writing por banda** para los bloques finales ≈ **70–80 ítems por idioma**.
  Suelo del piloto EN: 6/band de núcleo (30) + 3+3/band. Con 6/band y el
  balanceo por categoría, los mismos ~2 ítems de grammar de una banda salen
  siempre juntos → efecto memoria en re-tests; 8–10 lo diluye. Los bloques
  de listening/writing se administran solo en la banda final (y la adyacente
  si el resultado queda en el borde), así que 3/band alcanzan. Empezar con
  **EN y ES** (ambos catalogados A1–C1, verificado); el resto de idiomas en
  fase 4.
- Redacción: ítems tipo Cambridge/KET-PET-FCE (el "explain" de cada Exercise
  ya da la explicación tras el fallo, que es docencia gratuita).

### 4.2 Algoritmo — ladder por bandas con convergencia

Motor puro Kotlin `PlacementEngine` (patrón `CheckpointEngine`: sin Android,
testeable en JVM, determinista dado un historial de respuestas):

1. **Inicio**: el nivel de la lección recomendada si el usuario ya tiene
   progreso en el idioma (ya se calcula para la barra CEFR de Aprender —
   mejor señal que un B1 plano); A1 si empieza de cero. (Banda media = menos
   ítems para la mayoría.)
2. **Por pregunta**: se escoge el ítem no usado de la banda actual
   (priorizando la categoría con menos respuestas; gramática/vocab
   alternándose; reading intercalado cada 4–5).
3. **Subir/bajar banda**: con 2 aciertos de las últimas 3 en la banda actual →
   sube; con 2 fallos de las últimas 3 → baja (nunca por debajo de A1, nunca
   más arriba de C1). Un fallo en A1 no castiga: A1 es suelo.
4. **Parada**: banda determinada (subida confirmada y bajada fallida por
   debajo) **o** 18 ítems respondidos (tope duro 24).
5. **Bloques finales fijos** en la banda determinada: **listening** (3–4
   ítems con TTS) y **writing** (3–4 ítems de producción corta desde el
   significado). Cada bloque mantiene o rebaja **su propio desglose** una
   banda (2+ aciertos lo mantienen); nunca cambian la banda global del
   núcleo, solo el desglose que la UI muestra. Botón de repetir audio en
   listening (el TTS a veces falla; repetir es accesibilidad).
6. **Can-do siempre después** (decisión 2026-10-08): al terminar **o
   abandonar** el test, la autoevaluación CEFR se muestra siempre — es la
   señal garantizada incluso sin test completo.
7. **Resultado**: banda global + desglose por categoría (`CheckpointEngine`
   ya calcula porcentajes por categoría con `SkillsData.categories` y umbrales
   — se reutiliza su `grade()` sobre los ítems respondidos) + **confianza**
   (alta si convergió antes del tope, media si tocó 18, baja si se quedó en
   el borde de banda). El nivel recomendado **se sugiere, nunca se fija**
   (decisión 2026-10-08): alimenta la sugerencia de la barra CEFR de
   Aprender; la elección manual del usuario siempre gana y un re-test no
   sobreescribe un nivel elegido a mano sin confirmación.

Duración esperada: **12–18 ítems de núcleo ≈ 3–4 minutos** + ~1 min de
listening + ~1,5 min de writing + ~30 s de can-do ⇒ **objetivo ≤ 6 minutos
totales** (la métrica de calidad de §6 usa este objetivo).

### 4.3 Persistencia e integración — cero cambios de schema

- El ledger de progreso **ya tiene el kind `assessment`** (los checkpoints lo
  usan; el server lo acepta en `PROGRESS_KINDS`,
  `services/api/src/progress.ts:14`, y el sync lo incluye vía `rollupsOf`,
  `ProgressEngine.kt`). El resultado de placement se guarda como una entrada
  `assessment` con `itemID = "placement-<lang>-<version>"` (p. ej.
  `placement-en-1`) — **formato con guiones, no dos puntos**: el server
  valida `itemID` contra `/^[a-z0-9][a-z0-9_-]{0,63}$/` y rechazaría
  `placement:en:v1`. Además `bestScore` debe ser entero 0–100 y `attempts`
  ≥1, y como `assessmentsPassed` solo cuenta `bestScore >= 100`, un
  placement (que no saca 100) **no contamina** esa estadística. Heredamos
  gratis: almacenamiento local, sync multiplataforma (`user_progress`) y
  borrado GDPR. El desglose fino por categoría y la banda exacta del último
  intento viven en el archive JSON local (la recomendación usa el **último**
  intento; el ledger conserva el *mejor* percent como auditoría).
- **Integración con Aprender**: si existe placement, el nivel por defecto de
  la `LevelBar` pasa a ser el del placement (hoy: nivel de la lección
  recomendada); el usuario puede cambiarlo igual que ahora, y el cambio manual
  gana siempre. Tarjeta de entrada "¿Cuál es tu nivel? · Test de 4 min" bajo
  el bloque de hoy + fila de resultado en Ajustes ("Nivel estimado: B1 · 7
  oct · Repetir test").
- **Telemetría**: v1 sin eventos nuevos (el catálogo `usage_events` es
  cerrado y tocarlo es un cambio de contrato server+apps); se puede derivar
  del ledger `assessment`. En v2, eventos `placement_started/finished` si hay
  necesidad real de funnel.

### 4.4 UX de la pantalla del test

- Reutiliza los patrones visuales de `ExerciseScreen`/`CheckpointScreen`
  (mismos componentes, mismas colores, misma navegación atrás) con una barra
  de progreso *no comparativa* ("pregunta 7", sin % que invita a calcular) y
  **sin puntuación visible durante el test** (ansiedad cero).
- **Idioma de las instrucciones** (decisión 2026-10-08): nuevo ajuste
  **"Idioma de las instrucciones"** con valores `Subtítulos` (por defecto,
  comportamiento actual) o un idioma concreto de los que la app tiene
  traducidos (English/Español hoy). El placement lo consume; el ajuste es
  genérico y otras pantallas pueden adoptarlo después. Strings del test en
  EN y ES.
- Listening: aviso previo "vas a escuchar 3 frases" + botón de repetir audio
  (el TTS a veces falla; repetir es accesibilidad). Writing: prompt en el
  idioma de instrucciones ("Escribe en español: «me llamo Ana y soy de
  Madrid»") con teclado normal; corrección exacta con tolerancia de
  mayúsculas/puntuación, como el resto de ejercicios `written`.
- Flujo final (decisión 2026-10-08): resultado del test → **can-do
  siempre** (también si el test se abandonó: entonces es la única señal y
  produce una sugerencia solo autoevaluada) → pantalla final con banda
  sugerida, desglose por categoría, frase de confianza y **dos botones**:
  "Empezar en B1" (fija la selección actual de la barra y cierra) y "Elegir
  otro nivel" (abre la barra). Frase honesta de pie: "Es una estimación para
  recomendarte por dónde empezar; no es un certificado."

## 5. Plan de implementación (tras validar este informe)

| Fase | Contenido | Verificación |
|---|---|---|
| **F0 — Contenido (la crítica)** | Bancos **EN+ES**: núcleo 8–10/band + 3 listening + 3 writing por banda, redactados y revisados (hablante competente, como exige el repo para claims de pronunciación/enseñanza); `shared/placement/*.json` + exporter con su validación propia | Exporter verde, fixture compartida iOS/Android paritaria |
| **F1 — Motor** | `PlacementEngine.kt` (ladder + bloques finales + resultado) + modelo + loader; puro, determinista | Tests JVM: convergencia, bandas límite (todo-fallo→A1, todo-acierto→C1), no-repetición de ítems, rebajas de listening/writing solo en desglose, estabilidad ante reordenación |
| **F2 — UI + integración** | `PlacementScreen` (núcleo + can-do + resultado), tarjeta de entrada, sugerencia a la `LevelBar` (nunca fija), ajuste "Idioma de las instrucciones", strings EN/ES | Tests JVM del estado; smoke en emulador; UITest opcional |
| **F3 — Listening + Writing** | Bloques finales TTS y producción corta; rebajas por desglose | Manual por dispositivo (TTS varía); matching `written` con tests JVM |
| **F4 — Resto de idiomas + conversación de calibración (v2)** | Bancos de/it/fr/pt (+zh?); speaking y escritura libre por muestreo | Igual que F0 |

iOS: los motores son Kotlin; el espejo Swift (`PlacementEngine` en
`apps/ios/Core`) entra cuando la pantalla exista allí — el JSON compartido ya
es la contrato (patrión de los checkpoints).

## 6. Validación del propio test (antes de darlo por bueno)

1. **Revisión de contenido**: cada ítem con respuesta indiscutible y su banda
   justificada por can-do CEFR (A1: presente + yo/tú; A2: pasado cotidiano;
   B1: opiniones e hipótesis básicas; B2: matices y conectores complejos;
   C1: registro y estructura elaborada).
2. **Piloto interno**: los dos autores + 3–5 usuarios de prueba; registrar
   por consola de debug el trazado del ladder (banda por ítem) para ver
   convergencias y ítems trampa.
3. **Criterios de calidad**: duración media ≤ 6 min totales (núcleo ≤ 4);
   <10% de tests abandonados; correlación test vs autoevaluación ≥ ±1 banda
   en el 90%; estabilidad: la banda no cambia en el **80%** de los re-tests
   del mismo día (exigir el 100% con un banco de 40–50 ítems y efecto
   memoria es irreal; si fuera exigente, habría que subir el banco a
   15+/banda).
4. **Guardia anti-frustración**: si un ítem tiene >40% de fallos en pilotos,
   se re-bandea o se reescribe (el explain ayuda a detectarlo).

## 7. Decisiones — cerradas el 2026-10-08

1. **Alcance v1: EN + ES.** El test solo aparece en idiomas con banco
   validado; el resto llega en F4.
2. **Listening: dentro de v1** (bloque final TTS, construido en F3 tras el
   núcleo). **Writing: también dentro de v1** (delegado en mí): producción
   corta determinista con el `ExerciseEngine` — no redacción libre, que pasa
   a v2.
3. **Can-do: siempre tras el test**, se complete o se abandone — es la señal
   garantizada; si no hay test, produce una sugerencia solo autoevaluada.
4. **El resultado sugiere, nunca fija**: la elección manual del usuario manda
   y un re-test no sobreescribe un nivel elegido a mano sin confirmación.
5. **Instrucciones: idioma nativo configurable** — nuevo ajuste "Idioma de
   las instrucciones" (`Subtítulos` por defecto, o English/Español hoy);
   genérico para futuras pantallas.

## 8. Veredicto de la auditoría (2026-10-07, misma sesión)

Verificados los claims contra el código: `PROGRESS_KINDS` acepta `assessment`
(`services/api/src/progress.ts:14`) ✅; el sync incluye completions de
cualquier kind (`ProgressEngine.rollupsOf`) ✅; EN y ES catalogan A1–C1 ✅;
`CheckpointEngine`/`ExerciseEngine` reutilizables tal cual ✅. Encontrado y
corregido: (1) el `itemID` con dos puntos habría sido rechazado por la
validación SLUG del server — ahora `placement-<lang>-<version>`; (2) el
listening aparecía como "v1" en §3 pero como fase F3 en §5 — unificado: es
v1, construido en F3; (3) "checkers existentes cubren" era optimista — la
validación la hace el exporter nuevo; (4) banco 6/band era demasiado fino
para el balanceo por categoría — 8–10/band; (5) el criterio de estabilidad
al 100% era irreal con adaptividad + banco finito — 80%. **El informe queda
aprobado para implementación con estas correcciones.**

## 9. Fuentes

- [Duolingo — "Partial credit: improvements to Duolingo's placement test" (2018)](https://blog.duolingo.com) y [research.duolingo.com](https://research.duolingo.com) (diseño adaptativo, IRT y alineación CEFR del DET)
- [Cambridge English — adaptive testing / Linguaskill](https://www.cambridgeenglish.org) y [Research Matters 32: IRT y tests adaptativos (Benton, 2021)](https://www.cambridgeassessment.org.uk)
- [EF SET explained](https://www.ef.com/wwen/english-tests/efset) (adaptativo, escala 0–100 con equivalencia CEFR)
- [Busuu — cursos alineados CEFR A1–B2/C1](https://www.busuu.com/en/it-works/courses)
- [Fixed Form vs Adaptive Test Design in Language Proficiency Testing](https://sealofbiliteracy.org/blog/fixed-form-vs-adaptive-test-design-language-proficiency-testing)
- Council of Europe — CEFR self-assessment grid (can-do statements), base de la autoevaluación propuesta.
