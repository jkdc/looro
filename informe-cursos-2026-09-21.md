# Informe de cursos y temarios — aprender por lecciones estructuradas

**Fecha:** 2026-09-21 · **Rama:** `feature/switchable-conversation-engine`
**Alcance:** diseño (datos, entidades, clases y flujo usuario↔app) de la petición de "cursos por temarios con lecciones de gramática/expresiones, estudio previo cargado con lectura por voz, y conversación de práctica con el modelo", más la preparación del primer temario de iniciación.
**Base:** `informe-temario-2026-09-21.md` (§5-§6). Con tu petición eliges el enfoque **A** (contenido curado embebido) pero ampliado a estructura de **curso**, con dos piezas nuevas que el informe previo no contemplaba: **pantalla de estudio offline** (texto cargado + lectura TTS) y **práctica guiada con preguntas preestablecidas**.

---

## 1. Resumen ejecutivo

Lo que pides es un **curso estructurado por temarios** sobre el motor de conversación que ya existe: cada lección tiene (a) una **ficha de estudio cargada en la app** — introducción del punto gramatical + ejemplos — que se puede **leer o escuchar** sin gastar modelo, y (b) una **conversación de práctica** con un prompt riguroso y preguntas preestablecidas, donde el tutor indica qué está bien y qué no. Los cursos además llevan **temas extra enfocados** (la pieza `ConversationTheme` que ya tenemos) para conversación libre dentro del temario.

Dos decisiones de arquitectura lo hacen barato y robusto:

1. **El contenido es dato, no código de lógica.** Ya existe un pipeline canónico: el contenido vive en Swift (`apps/ios/Core/…`) y `scripts/export_android_content.py` genera el Kotlin + verifica paridad con `--check`. Los cursos siguen el mismo camino (nuevo `Courses.swift` → `Courses.kt` generado). Cero duplicación manual.
2. **La práctica reutiliza la sesión de voz tal cual.** Una sesión de práctica = `SessionRecord` con `lessonID` (campo nuevo **opcional**) + instrucciones nuevas en `TeachingPolicy` (misma vía que `theme()`/`greeting()`), que ya funciona igual en openai, deepseek/glm y hospedado. Nada nuevo fuera de `TeachingPolicy` + UI.

**Aclaración importante sobre "Whisper":** para **leer** el temario cargado no hace falta modelo ni Whisper — eso es **TTS** (síntesis de voz, ya existe: `SpeechSink`/`LocalTtsEngine` en Android, añadido multi-idioma en cabling-remediation T1). **Whisper es lo contrario** (voz→texto) y ya participa donde toca: transcribir tus respuestas durante la práctica en el motor on-device. El diseño queda: **leer la lección = TTS del texto cargado** (con botón de audio on/off como pides); **práctica = sesión de voz normal** donde en Android on-device Whisper transcribe al usuario y el modelo responde; el agente de IA **no** genera el material de estudio, solo conduce la práctica.

**Piloto asumido:** inglés (tu ejemplo es el verbo *to be* y "curso de inglés profesional"). El modelo de datos es multi-idioma desde el día uno (`languageID` en el curso).

---

## 2. Modelo de datos

### 2.1 Entidades de contenido (dato estático embebido, espejo Swift→Kotlin generado)

```swift
// apps/ios/Core/Courses.swift — canónico (edítalo aquí)
struct Course: Identifiable, Hashable, Sendable {
    var id: String            // "en-foundations"
    var languageID: String    // "en" — el curso pertenece a UN idioma
    var title: String         // "Foundations"
    var subtitle: String      // "Start from zero"
    var symbol: String        // SF Symbol / MuralSymbol key
    var description: String   // qué cubre y a quién va dirigido
    var units: [CourseUnit]
}
struct CourseUnit: Identifiable, Hashable, Sendable {
    var id: String            // "en-foundations-u1"
    var title: String         // "First sentences"
    var subtitle: String
    var lessons: [Lesson]     // el temario de la unidad, en orden
    var themes: [ConversationTheme]  // temas extra enfocados (reusa el tipo existente)
}
struct Lesson: Identifiable, Hashable, Sendable {
    var id: String            // "en-foundations-u1-to-be"
    var title: String         // "The verb “to be”"
    var goal: String          // objetivo comunicativo en 1 línea
    var intro: LessonIntro    // ficha de estudio cargada
    var expressions: [TargetExpression]  // 5–8 frases objetivo
    var situation: String     // situación que obliga a usar la forma (pedagogía de tarea)
    var practice: LessonPractice
}
struct LessonIntro: Hashable, Sendable {
    var explanation: [String]     // 2–4 párrafos MUY simples en el idioma del curso
    var examples: [ExampleLine]
}
struct ExampleLine: Hashable, Sendable { var text: String; var meaning: String } // meaning = glosa estable en inglés (convención glossary)
struct TargetExpression: Hashable, Sendable { var text: String; var meaning: String; var note: String }
struct LessonPractice: Hashable, Sendable {
    var questions: [PracticeQuestion]  // v1: preestablecidas, en orden
}
struct PracticeQuestion: Hashable, Sendable {
    var prompt: String        // pregunta del tutor, en el idioma del curso
    var expected: [String]    // respuestas aceptables (patrones de ejemplo)
    var focus: String         // qué evalúa ("short answers with am")
    var watch: String         // error típico a vigilar ("missing am: ‘I from Spain’")
}
```

Kotlin espejo `core/Courses.kt` **generado** extendiendo `export_android_content.py` (hoy exporta `Languages.kt`; se añade la exportación de `Courses.swift`). Así el contenido no puede divergir entre plataformas — mismo mecanismo con el que hoy no se desalinean los 8 idiomas. `--check` entra en la rutina de verificación.

**Registro:** `CourseRegistry.all: [Course]` / `CourseRegistry.forLanguage(id)`. Los temas extra de cada unidad son `ConversationTheme` de verdad (con su `situation`), así la práctica libre "enfocada al temario" reutiliza todo el cableado de temas que ya existe (incluido el steering que acabamos de meter en `themed-conversation-steering`).

### 2.2 Entidades de progreso (dato de usuario, Archive)

```kotlin
// Core espejado en Swift; campo NUEVO y OPCIONAL en Archive (convención del repo)
data class LessonState(
    val lessonID: String, val bars: Int,          // 0–3, misma escalera que WordState
    val attempts: Int, val lastSeen: Double, val dueAt: Double
)
// Archive: var lessons: MutableList<LessonState> = mutableListOf()  // Kotlin con default
// Swift:   var lessons: [LessonState]?                              // opcional → decodable tolera backups viejos
```

- **Convención crítica respetada:** todo campo nuevo es opcional (`Type?` en Swift, `= null`/default en Kotlin, **fuera** de las listas `fields(...)` de `ArchiveCodec`) — los backups previos siguen decodificando. No hace falta bump de `schemaVersion` si es opcional (misma regla que `slowSpeech`/`mode`).
- **`SessionRecord.lessonID: String? = null`** (opcional, misma regla) para marcar sesiones de práctica; `mode = "lesson"` reutiliza el patrón de `"listening"` para el prompt de apertura.
- **La evidencia sigue siendo sagrada:** en v1 la lección registra *intentos* (sesión completada). Las **barras 0–3 con `dueAt`** (repaso espaciado) se ganan con evidencia validada en la fase F3, extendiendo el assessment por pasaje igual que T2 del informe previo — nunca por impresión del tutor.

### 2.3 Prompts nuevos (`TeachingPolicy`, paridad verificada)

| Función | Uso | Texto (skeleton) |
|---|---|---|
| `lessonPractice(lesson, language)` | instrucciones base de la sesión de práctica | rol de profesor riguroso pero amable: hace las preguntas **en orden**, **una a una**, espera la respuesta, **dice si está bien o corrige** (recast + micro-regla), no avanza hasta validar, remata con mini-repaso |
| `lessonQuestions(lesson)` | las preguntas preestablecidas como datos dentro del prompt | numeradas, con `focus` y `watch` de cada una |
| `lessonOpening(lesson, language)` | comando de apertura (hermano de `greeting`) | "empieza presentando la lección en 2 frases y haz la pregunta 1" |
| `lessonSituation(lesson, language)` | tema extra enfocado → conversación libre | análogo a `theme()`; usable si el usuario abre un tema del curso sin sesión activa |

Mismo mecanismo de paridad que hoy: funciones de literal único, texto pineado por `check_cross_platform.py` (lección aprendida en `themed-conversation-steering`: el wording nuevo se saca de las interpolaciones a funciones comparables cuando importe).

---

## 3. Clases y pantallas nuevas

### Android (`apps/android`)

| Pieza | Dónde |
|---|---|
| `Courses.kt` (generado) + `CourseRegistry` | `core/` |
| `LessonState`, `Archive.lessons`, `SessionRecord.lessonID` | `core/Models.kt`, `core/LearningEngine.kt` |
| `TeachingPolicy.lesson*` | `core/TeachingPolicy.kt` |
| Tab 4 "Learn" en `FloatingNavigation` (hoy 3 tabs fijos, `maxWidth / 3`) | `ui/SoftControls.kt`, `ui/MuralApp.kt` |
| `CoursesScreen` (cursos → unidades → lecciones con estado), `LessonScreen` (ficha + audio) | `ui/LibraryScreens.kt` o archivo nuevo `ui/CoursesScreens.kt` |
| Lector TTS de la ficha (`SpeechSink` propio, `setLanguage(curso)`, botón on/off) | `MuralViewModel` + `core/voice/` |
| Arranque de práctica: `CloudAction.StartLesson(id)` → `start()` con `lessonID` + prompt | `MuralViewModel` |
| Icono nuevo de tab | `MuralSymbol` |

### iOS (`apps/ios`)

Espejo uno a uno: `Core/Courses.swift` (canónico), `Core/Models.swift` (`lessonID`, `lessons?`), `TeachingPolicy.swift`, `App/LibraryViews.swift` + `CoursesViews.swift`, `ConversationCoordinator.startLesson`, lector con `AVSpeechSynthesizer` (en iOS no hay pipeline on-device como en Android; el lector de fichas es un reproductor propio, independiente del motor de conversación).

### Strings

Toda UI nueva añade claves EN/ES en paridad (hoy 252/252); el contenido del curso NO va a strings — va a `Courses.swift`.

---

## 4. Flujo de acción usuario ↔ app

**Estudio (sin modelo, sin coste):**

1. Tab **Learn** → `CoursesScreen`: tarjeta del curso ("English · Foundations") con progreso agregado; dentro, unidades ordenadas y sus lecciones con estado (nueva / intentada / barras).
2. Tap en lección → `LessonScreen`: objetivo arriba, párrafos de explicación, ejemplos (texto + glosa), expresiones objetivo, y la situación enfocada como tarjeta.
3. Botón **altavoz** → el lector TTS lee explicación + ejemplos **en el idioma del curso** (voz del sistema, `setLanguage(locale)`; si no hay voz, degrada y queda legible — semántica de cabling T1). Botón **audio off** (tu requisito) → lectura silenciada, el usuario lee; el toggle persiste por sesión de app.
4. Temas extra de la unidad se listan al pie: abrir uno hoy = conversación libre enfocada (cableado de temas existente).

**Práctica (con el modelo):**

5. CTA **"Practice speaking"** en la lección → mismo consentimiento IA que hoy → `start()` crea sesión con `mode="lesson"`, `lessonID`, instrucciones `lessonPractice` + preguntas, y apertura `lessonOpening`.
6. El tutor: presenta la lección en 2 frases → pregunta 1 → **evalúa** tu respuesta siguiendo `expected`/`focus`/`watch`: dice "well done" o corrige con recast + micro-regla (p. ej. "almost — *I **am** from Spain*, don't forget *am*") → no avanza hasta que la respuesta sale → pregunta siguiente → al final, mini-repaso de las 3 expresiones clave.
7. Todo lo existente sigue funcionando igual en la sesión: subtítulos de significado, lookup de palabra, help, evaluación por pasaje, fin por botón/fondo.
8. La sesión se guarda con su `lessonID` (visible en la biblioteca como "Lesson: The verb to be") y `attempts += 1` en `LessonState`.

**Progreso (fases):**

9. v1: "practicada hoy / hace N días" por `lastSeen`.
10. F3: barras 0–3 solo con evidencia validada (extensión del assessment), y la lección "madura" con repaso espaciado; los temas extra del curso pueden sugerirse como repaso.

---

## 5. Fases openspec propuestas (SDD, como siempre)

| Fase | Contenido | Tests por fase |
|---|---|---|
| **F1 · Datos + estudio** | `Courses.swift` canónico (curso Foundations completo con L1), export a `Courses.kt`, tab Learn, `CoursesScreen`/`LessonScreen`, lector TTS con on/off, `LessonState` opcional en Archive | export `--check`, tests de registry, compatibilidad Archive (backup viejo decodifica), paridad strings |
| **F2 · Práctica** | `SessionRecord.lessonID`, `TeachingPolicy.lessonPractice/lessonOpening/lessonQuestions`, arranque desde la lección en ambos apps, typed-reply consciente de lección | tests JVM de policy (like themed-steering), checker, tests de arranque con fake transport |
| **F3 · Progreso con evidencia** | bloque `lesson` opcional en assessment + validación dura, barras/`dueAt` de `LessonState`, recap breve al cerrar práctica | fixtures cross-platform, tests de validación (no relajar evidencia) |
| **F4 · Contenido** | autoría del resto de lecciones del temario (2–3 por unidad) | revisión de hablante competente antes de claims (portiqueta del repo) |

**Fuera de alcance (parked):** cursos en otros idiomas hasta validar el piloto; quiz de opción múltiple; desbloqueo por unidades (navegación libre, recomendada); lecciones AI-generadas (T9).

---

## 6. Decisiones que tomé (cámbialas si no te convencen)

1. **Idioma del material de estudio:** explicaciones en **inglés muy simple** (comprehensible input, coherente con "Speak ONLY inglés" y con el contenido reutilizable en cualquier app). Los ejemplos llevan glosa en inglés (convención glossary). Si quieres las explicaciones traducidas al idioma de significados del usuario, existe una vía barata: reutilizar el helper de traducción existente sobre la ficha (opcional en F1).
2. **Tab nuevo "Learn"** en vez de colgar cursos dentro de Topics: 3 secciones ya tienen identidad propia y el curso es otra actividad. Toca tocar `FloatingNavigation` en ambas plataformas (está hardcodeado a 3).
3. **Preguntas preestablecidas v1** dentro del prompt (tu idea) + datos `expected/watch` para que la corrección sea consistente y auditable. Más adelante pueden evaluarse en cliente sin modelo (match contra `expected`) para el progreso con evidencia.
4. **Navegación libre** entre lecciones (sin candados), como recomendó el informe de temario.

---

## 7. Primer temario preparado: *English · Foundations* (iniciación)

Esquema completo (6 unidades × 3 lecciones) y **L1 totalmente autorada** como referencia del formato (JSON canónico que vivirá en `shared/courses/` o `Courses.swift` según se decida en el openspec). Contenido sujeto a revisión de hablante competente antes de claims públicos.

### Temario

- **U1 · First sentences** — L1 The verb “to be” ✅ autorada · L2 Questions & negatives with “to be” · L3 Introducing yourself
  - Temas extra: "Language-exchange café" · "First day in class"
- **U2 · People & things** — L4 a/an/the & plurals · L5 this/that & my/your · L6 “have got”: family
  - Temas extra: "Family photo album" · "The lost jumper"
- **U3 · Everyday actions** — L7 Present simple (I/you/we/they) · L8 He/she/it + -s & frequency · L9 Time & daily routine
  - Temas extra: "Monday morning" (existe) · "The morning rush"
- **U4 · Getting around** — L10 can/can’t · L11 there is / there are · L12 Directions & place
  - Temas extra: "Next stop" (existe) · "A new neighbourhood"
- **U5 · Food & likes** — L13 some/any & countables · L14 like/love/hate + -ing · L15 Ordering at a café
  - Temas extra: "A coffee?" (existe) · "The Sunday market"
- **U6 · The past begins** — L16 was/were · L17 Past simple -ed · L18 Your weekend (repaso)
  - Temas extra: "The weekend" (existe) · "A story that stayed" (existe)

### L1 · The verb “to be” (contenido completo)

```json
{
  "id": "en-foundations",
  "languageID": "en",
  "title": "Foundations",
  "subtitle": "Start from zero",
  "symbol": "graduationcap",
  "description": "Your first real English: the structures every sentence uses, practised out loud in small conversations.",
  "units": [
    {
      "id": "en-foundations-u1", "title": "First sentences", "subtitle": "Say who you are",
      "themes": [
        {"id": "lang-exchange", "title": "Language-exchange café", "subtitle": "Meet and greet", "symbol": "person.2", "category": "Course", "situation": "You are at a friendly language-exchange café. Meet the learner, exchange names, countries and jobs using the verb to be.", "colorIndex": 0}
      ],
      "lessons": [
        {
          "id": "en-foundations-u1-to-be",
          "title": "The verb “to be”",
          "goal": "Introduce yourself and ask simple questions with am/is/are.",
          "intro": {
            "explanation": [
              "Every English sentence needs a subject and a verb. The most important verb is “to be”. It tells you what someone is, how someone feels, or where something is.",
              "One verb, three present forms. With I use am: “I am Ana.” With you, we and they use are: “You are my friend.” With he, she and it use is: “She is a doctor.”",
              "In everyday talk, English shortens it: I'm, you're, he's, she's, it's, we're, they're. When you chat, say “I'm Ana”, not “I am Ana”."
            ],
            "examples": [
              {"text": "I'm Ana. I'm from Spain.", "meaning": "Your name and country."},
              {"text": "You are my best friend.", "meaning": "A close bond with someone."},
              {"text": "She is a doctor.", "meaning": "Someone's profession."},
              {"text": "We are very happy today.", "meaning": "A feeling right now."},
              {"text": "The café is near the station.", "meaning": "Where a place is."},
              {"text": "They are at work.", "meaning": "Someone's location."}
            ]
          },
          "expressions": [
            {"text": "I'm …", "meaning": "Introduce yourself.", "note": "With your name, age or feeling: I'm Ana. I'm tired."},
            {"text": "I'm from …", "meaning": "Say where you come from.", "note": "Never “I from …” — the am is obligatory."},
            {"text": "Are you …?", "meaning": "Ask about the other person.", "note": "Are you a student? Are you tired?"},
            {"text": "Yes, I am. / No, I'm not.", "meaning": "Short answers to an Are-you question.", "note": "Repeat the verb: never “Yes, I”."},
            {"text": "He's / She's a …", "meaning": "Talk about other people's jobs.", "note": "He's a teacher — the a matters with professions."},
            {"text": "Nice to meet you.", "meaning": "The fixed phrase when you meet someone.", "note": "Answer with the same phrase."}
          ],
          "situation": "You meet someone new at a friendly language-exchange café. Introduce yourselves and ask simple questions with the verb to be.",
          "practice": {
            "questions": [
              {"prompt": "Hello! I'm Ana. What's your name?", "expected": ["I'm <name>.", "My name is <name>."], "focus": "I am / My name is", "watch": "Answering only the name without the verb: accept it, then recast with “I'm …”."},
              {"prompt": "Where are you from?", "expected": ["I'm from <country>.", "I am from <country>."], "focus": "are + from", "watch": "“I from Spain” — recast “I am FROM Spain”, stress am."},
              {"prompt": "Are you a student?", "expected": ["Yes, I am.", "No, I'm not. I'm a <job>."], "focus": "short answers with am", "watch": "“Yes, I student” — the short answer needs am."},
              {"prompt": "How are you today?", "expected": ["I'm fine.", "I'm good.", "I'm tired."], "focus": "I'm + feeling", "watch": "“I fine” — recast “I'm fine”."},
              {"prompt": "Is your city big or small?", "expected": ["It's big.", "It's small.", "My city is …"], "focus": "is with things and places", "watch": "“My city big” — recast “My city IS big”."},
              {"prompt": "We're friends now! Where is your family from?", "expected": ["They're from <country>.", "My family is from <country>."], "focus": "they are / a group is", "watch": "“My family from …” — recast with is."}
            ]
          }
        }
      ]
    }
  ]
}
```

Rigor policy for the practice session (goes in `TeachingPolicy.lessonPractice`, not in the JSON): ask questions **in order, one at a time**; after each answer say explicitly whether it was correct ("Good — *I'm from Spain* is correct.") or correct it with a recast + one-line rule (using `watch`); don't advance until the target form appears (assisted is fine); accept any language for support but echo in English; finish with a 3-phrase mini-review.

---

## 8. Próximos pasos

1. Dime si las decisiones de §6 te encajan (idioma del material, tab "Learn", piloto inglés).
2. Lanzas el SDD (`lanza sdd cursos-temarios`) → creo `openspec/changes/courses-and-lessons/` y ejecuto F1→F2 por fases con tests y veredictos honestos (F3/F4 como cambios siguientes).
