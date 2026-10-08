# Diseño y lógica de la pantalla Aprender

## Estructura visual (de arriba a abajo)

```
┌─────────────────────────────────────────┐
│  APRENDER                               │  ← PageHeading (eyebrow + título + subtítulo)
│  Tu curso, paso a paso                  │
├─────────────────────────────────────────┤
│  [Repaso — N pendientes]                │  ← Recall card (solo si hay vencidas)
│  [Checkpoint listo]                     │  ← Checkpoint card (solo si está listo; SIEMPRE arriba)
│  [Tu entrenamiento de hoy + chips 5-20] │  ← Daily Plan card
├─────────────────────────────────────────┤
│  (A1) (A2) (B1) (B2) (C1)               │  ← LevelBar STICKY: selector CEFR (Busuu-style);
│         ^nivel actual en naranja        │     nivel completado lleva ✓; solo se ve UN nivel
├─────────────────────────────────────────┤
│  Foundations                            │  ← CourseLabel (etiqueta del curso en ese nivel)
│  ◉ Nivel 3 · barra de puntos            │  ← LevelCard (solo curso con lección recomendada)
│  ▾ First sentences        1/3 lecc ∨    │  ← Unidad (sin badge CEFR: el nivel ya está arriba)
│      · The verb "ser"      Practised 3× │  ← Solo la unidad activa expande sus lecciones
│  ▸ Mi día a día           0/3 lecc >    │
│  ▸ La comida              0/3 lecc >    │
└─────────────────────────────────────────┘
```

El catálogo dejó de ser una lista infinita: cada nivel CEFR es una vista
independiente (secciones estilo Duolingo) con selector explícito y fijado
(estilo Busuu). El nivel por defecto es el de la lección recomendada. La
selección de nivel y de unidad expandida sobreviven a rotación/navegación.

## Componentes y de dónde sacan los datos

| Componente | Fuente de datos | Fichero |
|---|---|---|
| **Recall card** | `vm.dueRecallCount()` → `RecallEngine` (palabras vencidas) | `LearnScreen.kt:95` |
| **Daily Plan card** | `vm.dailyPlan(budget)` → `DailyPlanEngine.plan()` → `recommendedLesson()` + `dueRecallCount()` + listening/reading sin usar | `LearnScreen.kt:207-249` |
| **Course card** | `CourseRegistry.forLanguage(language.id)` → JSON canónico en `shared/courses/` | `LearnScreen.kt:108-110` |
| **Level card** | `vm.levelStats(course)` → `LevelEngine.stats()` → proyección del ledger (gamificación 0-10) | `LearnScreen.kt:154-175` |
| **Unit sections** | `course.units` agrupadas por `unit.level` (A1, A2, B1, B2, C1) | `LearnScreen.kt:178-205` |
| **Lesson rows** | `unit.lessons` con `vm.lessonAttempts(id)` y `vm.recommendedLesson()` | `LearnScreen.kt:188-205` |
| **Checkpoint card** | `vm.checkpointReady()` → `CheckpointRegistry` + lecciones intentadas | `LearnScreen.kt:113-127` |
| **Skills (en progreso)** | `vm.masteryStats()` → `MasteryEngine.stats()` → proyección del ledger con decaimiento | `SettingsScreen.kt:460-474` |

## Flujo de datos

```
shared/courses/*.json (fuente canónica)
    ↓ export_courses.py
CoursesData.kt / CoursesData.swift (generado, nunca editar)
    ↓ CourseRegistry.forLanguage(languageID)
LearnScreen (LazyColumn)
    │
    ├── Para cada curso: CourseCard
    │       ├── LevelCard ← LevelEngine.stats(course, lessons, completions, listenings)
    │       │     └── puntos = Σ tiers de lecciones + Σ puntos de listening
    │       │         ladder = [1,6,12,19,27,37,49,61,69,78]
    │       │
    │       └── Para cada unidad: UnitSection
    │               └── Para cada lección: LessonRow
    │                       ├── tier (★★★) ← lessonTier()
    │                       ├── "Suggested next" ← recommendedLesson()
    │                       └── "New" / "Practised N×" ← archive.lessons
    │
    ├── DailyPlanCard ← DailyPlanEngine.plan(budget, dueWords, hasLesson, hasListening, hasReading)
    │
    └── CheckpointCard ← checkpointReady()
            └── levelLessons.all { attempted } && !checkpointPassed
```

## Sistema de niveles (gamificación, no CEFR)

```
Puntos = Σ lessonTiers + Σ listeningPoints

lessonTier:
  ★0 = sin práctica oral
  ★1 = práctica oral hecha (attempts ≥ 1)
  ★2 = ejercicios ≥ 60%
  ★3 = ejercicios ≥ 90% O práctica espaciada en 2 días

listeningPoints:
  0 = sin listening o mejor score < 80%
  1 = mejor score ≥ 80% (plata)
  2 = mejor score = 100% (oro)

Ladder: [1, 6, 12, 19, 27, 37, 49, 61, 69, 78]
Nivel = número de umbrales superados
Títulos: Newcomer → Foundations champion (10 niveles)

EN Foundations máximo teórico:
  43 lecciones × 3 pts = 129 (lecciones)
  41 listenings × 2 pts = 82 (listening)
  Total: 211 pts (suficiente para nivel 10 con holgura)

Otros idiomas máximo:
  6 lecciones × 3 pts = 18
  6 listenings × 2 pts = 12
  Total: 30 pts → nivel 4-5
```

## Sistema de mastery (capacidad, no gamificación)

```
MasteryEngine.stats():
  Por skill: mejor evidencia ponderada × decay temporal
    Pesos: checkpoint 1.0 · oral guiado 0.9 · ejercicios 0.6 · listening/reading 0.5
    Decay: 0.5^(días/60) — vida media de 60 días
  Por categoría: media de sus skills enseñados
  Global: media de categorías

  exposureSkills NO cuentan (lo que solo has oído no se mide)
  pron skills con flag OFF no cuentan
```

Se muestra en Settings → Tu progreso → Habilidades:
```
Dominio global: 42%
Gramática        ████████░░ 55%
Vocabulario      ██████░░░░ 38%
Funciones        ████████░░ 52%
...
```

## Sistema de checkpoints

```
Cada nivel tiene un checkpoint (banco de 16 preguntas mixtas).
Aparece cuando TODAS las lecciones de ese nivel tienen ≥ 1 intento.
Desaparece cuando se pasa: overall ≥ 70% Y cada categoría ≥ su suelo.

Al pasar: registra 1 mark de kind "assessment" en el ledger.
Al fallar: se puede reintentar (no registra el fallo).
```

## Recomendador (Suggested next)

```
recommendedLesson():
  1. Filtra lecciones no intentadas
  2. De ellas, la primera cuyos prerequisites tienen mastery ≥ 60
  3. Si ninguna cumple, la primera no intentada en orden del temario
```

## Daily Plan

```
dailyPlan(budget):
  1. Recall (si hay palabras vencidas) — máximo la mitad del presupuesto
  2. Lección recomendada — 1/3 del presupuesto
  3. Listening o reading sin usar — el resto
```

## Organización de unidades por idioma

| Idioma | Unidades | Niveles cubiertos | Lecciones |
|---|---|---|---|
| **en-foundations** | 25 | A1(12) A2(6) B1(4) B2(2) C1(1) | 77 |
| **es-foundations** | 5 | A1(3) A2(2) | 15 |
| **fr-foundations** | 4 | A1(2) A2(2) | 12 |
| **it-foundations** | 4 | A1(2) A2(2) | 10 |
| **de-foundations** | 4 | A1(2) A2(2) | 10 |
| **pt-foundations** | 4 | A1(2) A2(2) | 10 |
| **en-work-track** | 1 | B1 | 3 |
| **en-business-track** | 1 | B1 | 3 |
| **en-technology-track** | 1 | B1 | 3 |
| **en-hospitality-track** | 1 | B1 | 3 |
| **en-healthcare-track** | 1 | B1 | 3 |

Cada unidad contiene:
- **2 themes** de conversación (escenarios para Talk)
- **3-4 lecciones** con: explicación + ejemplos + 5-6 expresiones + situación de práctica + 5 preguntas de práctica + 4 ejercicios (choice/gap/order/written)
- **skill tags**: qué habilidades enseña, prerequisites, exposureSkills

## Skill tags y mastery

Cada lección declara:
```json
{
  "skills": ["grammar:present-simple", "function:talk-about-routines", "vocab:daily-routine"],
  "canDo": ["talk-about-routines"],
  "prerequisites": ["grammar:subject-pronouns"],
  "exposureSkills": ["grammar:past-continuous"]
}
```

- `skills` → lo que la lección enseña (cuenta para mastery)
- `prerequisites` → skills que debés dominar antes (el recomendador los usa)
- `exposureSkills` → estructuras que aparecen pero no se enseñan (NO cuentan para mastery)

El `SkillCatalogIndex` pre-construye estos mapeos una vez por carga de contenido.
```
