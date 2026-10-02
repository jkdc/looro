# Informe de diagramas — cómo quedó implementado courses-and-lessons

**Fecha:** 2026-09-21 · **Rama:** `feature/switchable-conversation-engine`
**Alcance:** documentación visual (diagramas) de la funcionalidad implementada en `openspec/changes/courses-and-lessons/` (commits `9909890`→`08160b1`): modelo de datos, almacenamiento real (no hay BD SQL: contenido compilado + Archive JSON local) y flujos usuario↔app. Diagramas en Mermaid, renderizables en GitHub/VS Code.

---

## 1. Arquitectura general

El contenido de los cursos es **dato compilado dentro de la app** (autoría una vez, generado a las dos plataformas); el progreso es **Archivo JSON local**; la práctica reutiliza la sesión de voz existente.

```mermaid
flowchart LR
    subgraph AUTORÍA["Autoría de contenido (una vez)"]
        JSON["shared/courses/en-foundations.json<br/>(JSON canónico)"]
        GEN["scripts/export_courses.py<br/>(valida esquema, --check)"]
    end
    subgraph CORE["Core espejado (lógica compartida)"]
        DATAK["CoursesData.kt<br/>(GENERADO)"]
        DATAS["CoursesData.swift<br/>(GENERADO)"]
        SHAPES["Courses.kt / Courses.swift<br/>(structs + CourseRegistry)"]
        POLICY["TeachingPolicy<br/>lessonPractice · lessonOpening · typedReply"]
    end
    subgraph APPUI["Capa de app"]
        UIK["LearnScreen.kt<br/>(tab Aprender, Compose)"]
        UIS["CoursesViews.swift<br/>(tab Learn, SwiftUI)"]
        VMK["LooroViewModel<br/>selectLesson · lector TTS · attempts"]
        COORD["ConversationCoordinator<br/>selectLesson ·AVSpeech · attempts"]
    end
    subgraph SESSION["Sesión de práctica (voz)"]
        TRANSPORT["VoiceTransport<br/>openai realtime | glm+whisper | hospedado"]
        MODEL["Modelo de IA"]
    end
    subgraph STORE["Almacenamiento local (JSON)"]
        ARCHK["Archive<br/>learning.json (AtomicFile)"]
        ARCHS["Archive<br/>Application Support"]
    end

    JSON --> GEN --> DATAK & DATAS --> SHAPES
    SHAPES --> UIK & UIS
    UIK --> VMK --> POLICY
    UIS --> COORD --> POLICY
    POLICY --> TRANSPORT <--> MODEL
    VMK --> ARCHK
    COORD --> ARCHS
```

Garantía de no divergencia: `export_courses.py --check` + `check_cross_platform.py` (palabra de los prompts y campos de Archive).

---

## 2. Modelo de datos

### 2.1 Contenido embebido (solo lectura, compilado en la app)

```mermaid
erDiagram
    COURSE ||--o{ COURSE_UNIT : "units"
    COURSE_UNIT ||--o{ LESSON : "lessons (ordenadas)"
    COURSE_UNIT ||--o{ CONVERSATION_THEME : "temas extra enfocados"
    LESSON ||--|| LESSON_INTRO : "ficha de estudio"
    LESSON_INTRO ||--o{ EXAMPLE_LINE : "examples"
    LESSON ||--o{ TARGET_EXPRESSION : "expresiones objetivo"
    LESSON ||--|| LESSON_PRACTICE : "practice"
    LESSON_PRACTICE ||--o{ PRACTICE_QUESTION : "preguntas preestablecidas"

    COURSE {
        string id "en-foundations"
        string languageID "en (1 curso por idioma)"
        string title
        string description
    }
    COURSE_UNIT {
        string id "en-foundations-u1"
        string title
    }
    LESSON {
        string id "en-foundations-u1-to-be"
        string title "The verb “to be”"
        string goal "objetivo comunicativo"
        string situation "situación de práctica"
    }
    LESSON_INTRO {
        list explanation "párrafos en inglés simple"
    }
    EXAMPLE_LINE {
        string text
        string meaning "glosa estable en inglés"
    }
    TARGET_EXPRESSION {
        string text
        string meaning
        string note "cuándo/usos"
    }
    PRACTICE_QUESTION {
        string prompt "pregunta del tutor"
        list expected "respuestas aceptables"
        string focus "qué evalúa"
        string watch "error típico a vigilar"
    }
```

`CONVERSATION_THEME` es el tipo que ya existía para Temas: los temas del curso se abren con el mismo cableado (steering incluido).

### 2.2 Datos de usuario (Archive local — lo nuevo en negrita)

```mermaid
erDiagram
    ARCHIVE ||--|{ SESSION_RECORD : "sessions"
    ARCHIVE ||--o{ LESSON_STATE : "lessons (NUEVO, opcional)"
    ARCHIVE ||--|| PREFERENCES : ""
    SESSION_RECORD ||--o{ FRAGMENT : "transcripción"
    SESSION_RECORD ||--o{ ASSESSMENT : "evidencia validada"

    ARCHIVE {
        int schemaVersion "2 (campos nuevos opcionales)"
    }
    SESSION_RECORD {
        string mode "lesson | listening | null"
        string lessonID "NUEVO · FK lógica a Lesson.id"
        string themeID
        string title "título de la lección en práctica"
    }
    LESSON_STATE {
        string lessonID "FK lógica a Lesson.id"
        int bars "0 (reservado, fase evidencia)"
        int attempts "NUEVO · nº de prácticas"
        double lastSeen "unix seconds"
        double dueAt "0 (reservado)"
    }
```

**Relación lógica entre ambos mundos** (no hay FK real; se resuelve con `CourseRegistry.lesson(id)`):

```mermaid
flowchart LR
    L["Lesson.id<br/>(contenido, dato)"] -. "SessionRecord.lessonID<br/>(sesiones de práctica)" .-> S["SessionRecord"]
    L -. "LessonState.lessonID<br/>(progreso por lección)" .-> LS["LessonState"]
```

Reglas del Archive que se respetan: campos nuevos **opcionales** (los backups anteriores siguen importando), `lessons` limitado a 1000 entradas, fusión de importes por **máximo progreso**, fixture compartido `shared/fixtures/cross-platform/archive.json` que pina los campos en ambas plataformas.

---

## 3. Cómo se almacenan los datos (no hay base de datos)

| Dato | Dónde vive | Formato | Escritura |
|---|---|---|---|
| Contenido de cursos | Compilado en el binario de cada app (`CoursesData.kt`/`.swift`) | Kotlin/Swift generado desde JSON | `scripts/export_courses.py` (autoría) |
| Progreso e historial | **Fichero JSON local** — Android: `filesDir/learning.json` (AtomicFile + `.bak`); iOS: Application Support vía `LearningStore.persist()` | `Archive` JSON, `schemaVersion: 2` | Cola de guardado (`LearningRepository`) / `persist()` inmediato |
| Fixtures de prueba | `shared/fixtures/cross-platform/*.json` | JSON compartido | manual (contract tests) |

Estructura (recortada) del Archive en disco con lo nuevo:

```json
{
  "schemaVersion": 2,
  "preferences": { "learningLanguageID": "en", "...": "..." },
  "sessions": [
    { "id": "…", "mode": "lesson", "lessonID": "en-foundations-u1-to-be",
      "title": "The verb “to be”", "fragments": [...], "assessments": [...] }
  ],
  "lessons": [
    { "lessonID": "en-foundations-u1-to-be", "bars": 0,
      "attempts": 3, "lastSeen": 1758470400.0, "dueAt": 0.0 }
  ]
}
```

Sin nube, sin analytics: todo permanece en el dispositivo (invariante del producto).

---

## 4. Flujo de estudio (sin modelo de IA, sin coste)

```mermaid
sequenceDiagram
    actor U as Usuario
    participant UI as Learn tab<br/>(LearnScreen / CoursesView)
    participant VM as ViewModel / Coordinator
    participant TTS as TTS local<br/>(SpeechSink / AVSpeech)

    U->>UI: Abre "Aprender"
    UI->>VM: CourseRegistry.forLanguage(idioma)
    UI-->>U: Curso → unidades → lecciones ("Nueva" / "Practicada N×")
    U->>UI: Toca la lección "The verb to be"
    UI->>VM: audio activado → readLessonCard(título + explicación + ejemplos)
    VM->>TTS: initialize + setLanguage("en-US") + speak
    TTS-->>U: lee la ficha en inglés (si no hay voz → silencio, queda legible)
    Note over U: También puede leerla en silencio<br/>(switch "Leer en voz alta" = off)
    U->>UI: Toca un tema extra del curso
    UI->>VM: chooseTheme(tema) → pestaña Talk (conversación libre enfocada)
```

## 5. Flujo de práctica (con el modelo de IA)

```mermaid
sequenceDiagram
    actor U as Usuario
    participant UI as Lesson card
    participant APP as App (consent + mic)
    participant SES as Sesión de voz<br/>mode="lesson", lessonID
    participant M as Modelo (prompt lessonPractice)
    participant A as Archive

    U->>UI: "Practicar hablando"
    UI->>APP: StartLesson(lessonID) → consentimiento → permiso mic
    APP->>SES: start() · instrucciones = lessonPractice(6 preguntas numeradas)
    SES->>M: apertura lessonOpening ("presenta la lección y haz la pregunta 1")
    loop pregunta a pregunta (en orden)
        M->>U: pregunta k (+ focus/watch internos)
        U->>SES: responde (voz; whisper transcribe en glm / realtime en openai)
        M->>U: "well done" o recast + micro-regla · NO avanza hasta acertar (acepta asistido)
    end
    M->>U: mini-repaso de las expresiones clave
    U->>APP: cuelga / background
    APP->>A: sesión guardada con lessonID · LessonState.attempts += 1
    Note over A: El progreso con barras/dueAt (evidencia<br/>validada) es la fase siguiente, no esta
```

### Cableado del prompt según motor (transparente para la lección)

```mermaid
flowchart TB
    P["TeachingPolicy.lessonPractice + lessonOpening"]
    P -->|CONVERSATION_ENGINE=openai| RT["LiveTransport<br/>OpenAI Realtime (instructions + session.instructions.append)"]
    P -->|glm/deepseek on-device| LOC["LocalVoiceTransport<br/>prompt reconstruido por turno (whisper STT + TTS es)"]
    P -->|hospedado| HOS["Hosted client<br/>mismos helpers en el backend"]
```

---

## 6. Resumen de una línea por pieza

| Pieza | Fichero(s) | Rol |
|---|---|---|
| Autoría | `shared/courses/*.json` | única fuente de contenido |
| Generador | `scripts/export_courses.py` | valida + emite ambas plataformas + `--check` |
| Formas/registro | `core/Courses.kt` · `Core/Courses.swift` | structs + `CourseRegistry` |
| Prompts | `TeachingPolicy.kt`/`.swift` | `lessonPractice`, `lessonOpening`, `typedReply(lesson)` |
| Progreso | `LessonState` en `Archive` (ambos cores) | intentos por lección (barras reservadas) |
| UI Android | `ui/LearnScreen.kt`, `LooroViewModel`, `CloudAction.StartLesson` | tab Aprender + lector TTS + práctica |
| UI iOS | `App/CoursesViews.swift`, `ConversationCoordinator`, `Storage.swift` | tab Learn + AVSpeech + práctica |
| Gates | `check_cross_platform.py`, `export_courses.py --check`, fixture compartido | paridad de prompts, datos y Archive |
