# Lógica de Mastery, caché e invalidación

## 1. Objetivo

Este documento define la lógica que debe aplicarse para calcular, cachear e invalidar el `mastery` del usuario sin introducir estado duplicado innecesario.

La decisión principal es:

> **El ledger/evidence es la fuente de verdad. El mastery es siempre una proyección derivada.**

Cualquier caché o snapshot debe ser reconstruible. Si existe cualquier duda sobre su validez, se descarta y se recalcula desde la fuente de verdad.

---

## 2. Principios

1. `Ledger / Evidence` es autoritativo.
2. `Mastery` nunca es estado primario.
3. Una caché puede eliminarse sin perder progreso.
4. El sync debe operar sobre evidencia, no sobre mastery cacheado.
5. Los cambios de algoritmo o de mappings de skills deben invalidar la caché.
6. El decay temporal debe calcularse sin reescribir mastery continuamente.
7. El backend podrá almacenar snapshots en el futuro, pero no es prioritario.

---

## 3. Arquitectura objetivo

```text
                         CONTENT
                            │
                     SkillCatalogIndex
                            │
                            ▼
USER ACTIVITY ───────► LEARNING LEDGER
                            │
                            │ source of truth
                            ▼
                     MasteryEngine
                            │
                            ▼
                     Raw Mastery
                            │
                            ▼
                      apply decay
                            │
                            ▼
                  Effective Mastery
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
           Progress       Recall       Daily Plan
              UI                       Recommendations
```

En una fase futura:

```text
SERVER LEARNING EVENTS
          │
          ▼
 Server Mastery Projector
          │
          ▼
 user_skill_mastery
```

La tabla de backend será una **materialized projection**, no la fuente de verdad.

---

# 4. Qué implementar ahora

La primera versión debe implementar únicamente:

1. `SkillCatalogIndex`
2. `ledgerRevision`
3. `masteryVersion`
4. memoización en memoria de `RawMasteryStats`
5. cálculo de decay en lectura
6. métricas de rendimiento

No es prioritario implementar ahora:

- snapshot persistente local;
- dirty skills persistentes;
- snapshot de mastery en backend;
- sincronización de mastery entre dispositivos.

---

# 5. SkillCatalogIndex

El cálculo de mastery no debería recorrer todo el catálogo cada vez para descubrir los skills asociados a cada actividad.

Al cargar el contenido se debe crear un índice:

```text
activityID → skillIDs
skillID → activityIDs
```

Ejemplo conceptual:

```swift
struct SkillCatalogIndex {
    let skillsByActivityID: [String: Set<String>]
    let activitiesBySkillID: [String: Set<String>]
}
```

Puede ampliarse con:

```text
skillsByLessonID
skillsByListeningID
skillsByTalkScenarioID
skillsByCheckpointID
```

El índice se construye una vez por carga/versionado de contenido y se reutiliza.

---

# 6. Evolución del ledger hacia evidence

A largo plazo las nuevas evidencias deberían guardar directamente los skills afectados.

Ejemplo:

```json
{
  "kind": "exercise_set",
  "itemID": "en-foundations-u3-present",
  "language": "en",
  "skillIDs": [
    "en:grammar:present-simple",
    "function:describe-routine"
  ],
  "score": 0.92,
  "timestamp": "2026-09-30T15:00:00Z"
}
```

Compatibilidad:

```text
si evidence.skillIDs existe:
    usarlo directamente
si no:
    resolver itemID mediante SkillCatalogIndex
```

Así no es necesario migrar inmediatamente todo el ledger existente.

---

# 7. ledgerRevision

Cada cambio efectivo del ledger debe incrementar una revisión monotónica.

```text
ledgerRevision = 1842

nueva evidencia

ledgerRevision = 1843
```

La revisión debe cambiar cuando:

- se añade evidencia;
- mejora una evidencia;
- se hace merge de sync;
- se elimina evidencia válida;
- se restaura/importa historial;
- cambia un evento que afecta a mastery.

No debe cambiar por consultas o cambios visuales.

---

# 8. masteryVersion

El ledger puede permanecer igual y aun así cambiar el resultado de mastery.

Ejemplos:

- cambia el peso de Talk;
- cambia el algoritmo;
- cambia el decay;
- cambia el mapping actividad → skill;
- cambia la definición de un skill.

Por eso debe existir:

```text
masteryVersion
```

Ejemplo:

```swift
let masteryVersion = 4
```

Cada cambio capaz de alterar el resultado del cálculo incrementa esta versión.

Más adelante puede separarse en:

```text
algorithmVersion
skillCatalogVersion
```

pero para v1 una única `masteryVersion` es suficiente.

---

# 9. Memoización

Guardar en memoria:

```text
cachedLedgerRevision
cachedMasteryVersion
cachedRawMasteryStats
```

Al consultar mastery:

```text
if cachedLedgerRevision == ledger.revision
   && cachedMasteryVersion == currentMasteryVersion:

    reutilizar cachedRawMasteryStats
else:
    recalcular
    guardar nueva cache
```

Esto hace que abrir varias veces la hoja de progreso tenga coste prácticamente constante si no ha ocurrido nueva actividad.

---

# 10. Flujo v1

```text
Usuario abre "Tu progreso"
        │
        ▼
MasteryEngine.stats()
        │
        ▼
¿cache válida?
    │         │
    sí        no
    │         │
    ▼         ▼
 reutilizar  recalcular ledger
    │         │
    └────┬────┘
         ▼
   aplicar decay
         │
         ▼
      devolver
```

---

# 11. Raw Mastery vs Effective Mastery

Debe distinguirse:

## Raw Mastery

Lo que el usuario ha demostrado mediante sus evidencias.

## Effective Mastery

La estimación actual después de aplicar decaimiento temporal.

Ejemplo:

```text
rawMastery       = 91
lastPracticedAt  = hace 45 días
effectiveMastery = 77
```

---

# 12. Decay temporal

Mastery puede cambiar sin cambios en el ledger.

Por tanto:

```text
ledger sin cambios
≠
effective mastery necesariamente idéntico
```

No debe persistirse diariamente:

```text
91 → 90 → 89 → 88
```

Se debe persistir/calcular:

```json
{
  "rawMastery": 91,
  "lastPracticedAt": "2026-08-10T09:00:00Z"
}
```

y al consultar:

```text
effectiveMastery =
    decay(rawMastery, lastPracticedAt, now)
```

La parte cara que se memoiza es `rawMastery`.

---

# 13. Dirty Skills

Una actividad puede afectar varios skills.

Ejemplo:

```text
Talk: "Tell me about your weekend"
```

puede generar evidencia para:

```text
en:grammar:past-simple
en:grammar:past-irregular
speaking:tell-past-story
vocab:weekend
en:pron:past-ed
```

Por tanto el diseño futuro debe trabajar con:

```text
dirtySkills: Set<SkillID>
```

Flujo futuro:

```text
nueva evidencia
      │
      ▼
extraer skillIDs
      │
      ▼
dirtySkills
      │
      ▼
recalcular solo esos skills
```

No es necesario implementar esta actualización incremental mientras el recálculo completo siga siendo barato.

---

# 14. Mastery no debe depender solo de bestScore

Conservar únicamente la mejor evidencia puede sobreestimar dominio.

Ejemplo:

```text
enero    100 %
febrero   45 %
marzo     50 %
abril     55 %
```

El usuario no debería conservar un mastery cercano a 100 únicamente por el resultado de enero.

A largo plazo:

```text
mastery =
evidence value
× evidence strength
× recency
× independence
```

Pesos conceptuales:

```text
multiple choice        bajo
gap                    medio
written                medio-alto
guided speaking        alto
spontaneous speaking   muy alto
checkpoint             muy alto
```

Los pesos exactos se calibrarán con datos reales.

---

# 15. Hints

Una respuesta correcta sin ayudas debe aportar más evidencia que una respuesta correcta después de varias pistas.

Campos futuros:

```json
{
  "score": 1.0,
  "hintsUsed": 2,
  "evidenceType": "guided_speaking"
}
```

---

# 16. Dimensiones futuras

Un skill puede dominarse en reconocimiento pero no en producción espontánea.

Ejemplo futuro:

```json
{
  "skillID": "en:grammar:present-simple",
  "rawMastery": 84,
  "dimensions": {
    "recognition": 95,
    "production": 81,
    "spontaneous": 69
  }
}
```

No es necesario implementar estas dimensiones en v1, pero el modelo no debe impedirlas.

---

# 17. API conceptual de MasteryEngine

Ejemplo:

```swift
struct SkillMastery {
    let skillID: String

    let rawMastery: Double
    let effectiveMastery: Double

    let evidenceCount: Int
    let lastPracticedAt: Date?

    let category: SkillCategory
}
```

```swift
struct MasteryStats {
    let overall: Double
    let categories: [SkillCategory: Double]
    let skills: [String: SkillMastery]
}
```

---

# 18. Scope del mastery

`overall` no debe ser la media de absolutamente todos los skills del catálogo.

El cálculo debe hacerse respecto a un scope.

Ejemplos:

```text
mastery(language: en, scope: A1)
mastery(language: en, scope: A2)
mastery(language: en, scope: course)
```

Esto permite calcular:

```text
A1 readiness
A2 readiness
```

sin contaminar el resultado con skills futuros o profesionales.

---

# 19. Mastery por categorías

El motor debe soportar agregados para:

```text
Grammar
Vocabulary
Listening
Speaking
Reading
Writing
Pronunciation
```

Ejemplo:

```text
Grammar        82
Vocabulary     76
Listening      68
Speaking       61
Pronunciation  49
```

---

# 20. Invalidación local

La caché es válida únicamente cuando:

```text
cachedLedgerRevision == currentLedgerRevision
AND
cachedMasteryVersion == currentMasteryVersion
```

Ejemplo:

```text
cached ledgerRevision = 104
current ledgerRevision = 105
→ invalid
```

o:

```text
cached masteryVersion = 3
current masteryVersion = 4
→ invalid
```

---

# 21. Sync entre dispositivos

El sync sigue ocurriendo sobre el ledger/evidence.

```text
local ledger
+
remote ledger
↓
merge
↓
ledgerRevision++
↓
mastery cache invalidada
```

No se sincronizan caches de mastery entre dispositivos.

Esto evita conflictos como:

```text
iPhone snapshot = 84
Android snapshot = 81
```

Cada dispositivo puede derivar su mastery desde el historial sincronizado.

---

# 22. Snapshot local persistente — fase posterior

Cuando sea necesario puede añadirse:

```text
LocalMasterySnapshot
```

Ejemplo:

```json
{
  "language": "en",
  "ledgerRevision": 1842,
  "masteryVersion": 4,
  "computedAt": "2026-09-30T15:00:00Z",
  "skills": {
    "en:grammar:present-simple": {
      "rawMastery": 84,
      "evidenceCount": 34,
      "lastPracticedAt": "2026-09-29T10:00:00Z"
    }
  }
}
```

Validación:

```text
snapshot.ledgerRevision == ledger.revision
AND
snapshot.masteryVersion == currentMasteryVersion
```

Si no coincide:

```text
rebuild
```

Si está corrupto:

```text
ignore/delete
↓
rebuild
```

Eliminar un snapshot nunca debe producir pérdida de progreso.

---

# 23. Snapshot backend — diseñado, no prioritario

El backend puede incorporar más adelante:

```sql
CREATE TABLE user_skill_mastery (
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  language TEXT NOT NULL,
  skill_id TEXT NOT NULL,

  raw_mastery SMALLINT NOT NULL
    CHECK (raw_mastery BETWEEN 0 AND 100),

  evidence_count INTEGER NOT NULL DEFAULT 0,
  last_practiced TIMESTAMPTZ,

  source_revision BIGINT NOT NULL,
  mastery_version INTEGER NOT NULL,

  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  PRIMARY KEY (account_id, language, skill_id)
);
```

Semántica obligatoria:

> `user_skill_mastery` es una materialized projection reconstruible.

No es la fuente de verdad.

---

# 24. Cómo debe actualizarse el snapshot backend

No se recomienda:

```text
cliente calcula mastery
↓
push mastery
↓
servidor lo acepta como verdad
```

Se recomienda:

```text
cliente sincroniza evidence
↓
servidor persiste/mergea evidence
↓
server projector actualiza mastery
```

Así cliente y servidor derivan sus estados desde la misma información autoritativa.

---

# 25. Cuándo implementar el snapshot backend

Solo cuando exista un consumidor real.

Ejemplos:

- recomendaciones server-side;
- dashboards pedagógicos;
- análisis de cohortes;
- personalización cross-device;
- consultas frecuentes de mastery sin cargar historial.

No implementarlo únicamente por previsión.

---

# 26. Mastery y Analytics son sistemas diferentes

El snapshot de mastery permite responder:

```text
¿Qué porcentaje de usuarios tiene mastery < 50
en past simple?
```

Pero no permite responder por sí solo:

```text
¿Qué ejercicio genera más errores?
¿Qué lección tiene más abandono?
```

Para eso se necesitan eventos de analytics.

Ejemplos:

```text
lesson_started
lesson_completed
exercise_answered
listening_completed
talk_error_detected
checkpoint_completed
```

Separación:

```text
Learning Evidence
→ qué ha demostrado el usuario

Mastery
→ qué estimamos que domina

Analytics Events
→ cómo se comporta dentro del producto
```

---

# 27. Triggers de escalado

No utilizar como trigger principal:

```text
número de unidades
```

Medir directamente:

```text
MasteryEngine.stats() latency
```

Especialmente:

```text
p50
p95
p99
```

en dispositivos reales.

---

# 28. Trigger para snapshot local

Implementar snapshot persistente cuando:

```text
p95 > 30–50 ms
```

en dispositivos objetivo,

o cuando mastery empiece a consultarse continuamente desde:

- Home;
- Recall;
- Talk;
- Daily Plan;
- Recommendations;
- Progress.

Esta segunda condición puede llegar antes que un problema puro de CPU.

---

# 29. Trigger para dirty skills

Implementar actualización incremental cuando:

```text
recalcular todo el ledger
```

sea claramente más caro que:

```text
recalcular skills afectados
```

o cuando mastery se actualice continuamente durante una sesión.

---

# 30. Trigger para backend

Implementar `user_skill_mastery` cuando exista al menos un consumidor server-side real.

Hasta entonces:

```text
BE snapshot = diseño preparado
```

pero fuera de prioridades inmediatas.

---

# 31. Fases recomendadas

## Fase 1 — ahora

Implementar:

- `SkillCatalogIndex`;
- `ledgerRevision`;
- `masteryVersion`;
- memoización de raw mastery;
- decay en lectura;
- métricas de rendimiento.

## Fase 2 — evidence enriquecida

Añadir progresivamente:

- `skillIDs`;
- `score`;
- `timestamp`;
- `hintsUsed`;
- `evidenceType`.

## Fase 3 — snapshot local

Solo cuando métricas o frecuencia de uso lo justifiquen.

## Fase 4 — dirty skills

Actualizar únicamente skills afectados.

## Fase 5 — snapshot backend

No prioritario.

Implementarlo cuando existan analytics/recomendaciones/servicios que realmente lo necesiten.

---

# 32. Ejemplo de flujo completo v1

Usuario completa:

```text
exercise_set
itemID = en-foundations-u3-present
score = 90 %
```

### Paso 1

Guardar en ledger.

```text
ledgerRevision:
103 → 104
```

### Paso 2

La cache anterior queda inválida automáticamente.

```text
cachedRevision = 103
currentRevision = 104
```

### Paso 3

Una pantalla solicita mastery.

```text
MasteryEngine.stats()
```

### Paso 4

Resolver skills mediante `SkillCatalogIndex`.

### Paso 5

Calcular raw mastery desde evidence.

### Paso 6

Guardar:

```text
cachedRevision = 104
cachedMasteryVersion = 3
cachedRawStats = ...
```

### Paso 7

Aplicar decay usando `now`.

### Paso 8

Devolver effective mastery.

---

# 33. Segunda consulta

El usuario vuelve a abrir progreso.

No hay nueva actividad.

```text
cachedRevision = 104
ledgerRevision = 104
```

No se vuelve a recorrer el ledger.

Se reutiliza raw mastery y solo se aplica decay temporal.

---

# 34. Sync posterior

Llega actividad de otro dispositivo.

```text
merge
↓
ledgerRevision 104 → 105
```

La cache queda inválida.

La siguiente consulta reconstruye mastery.

---

# 35. Cambio de algoritmo

Nueva versión de la app:

```text
masteryVersion 3 → 4
```

Aunque el ledger no cambie:

```text
cachedMasteryVersion != currentMasteryVersion
```

Resultado:

```text
rebuild
```

---

# 36. Invariantes

Estas reglas no deben romperse.

### Invariante 1

```text
ledger/evidence = source of truth
```

### Invariante 2

Todo mastery debe ser reconstruible.

### Invariante 3

Eliminar cachés o snapshots nunca puede eliminar progreso.

### Invariante 4

El sync ocurre sobre evidence, no sobre caches.

### Invariante 5

Los cambios de algoritmo invalidan mastery anterior.

### Invariante 6

Los cambios de skill mappings pueden invalidar mastery anterior.

### Invariante 7

El decay temporal no requiere escrituras periódicas.

### Invariante 8

El backend nunca debe convertirse en una segunda verdad independiente.

---

# 37. Tests mínimos

## Cache hit

```text
same ledgerRevision
same masteryVersion
→ no recompute
```

## Ledger changed

```text
different ledgerRevision
→ recompute
```

## Algorithm changed

```text
different masteryVersion
→ recompute
```

## Decay

```text
same raw mastery
different now
→ effective mastery puede cambiar
```

## Sync

```text
remote merge
→ ledgerRevision cambia
→ cache invalidada
```

## Completion antigua

```text
sin skillIDs
→ resolver mediante SkillCatalogIndex
```

## Evidence nueva

```text
con skillIDs
→ usar directamente
```

## Snapshot futuro corrupto

```text
ignore
→ rebuild
```

---

# 38. Observabilidad

Medir desde el inicio:

```text
mastery_calculation_duration_ms
ledger_event_count
mastery_skill_count
mastery_cache_hit
mastery_cache_miss
```

Y especialmente:

```text
MasteryEngine.stats p50
MasteryEngine.stats p95
MasteryEngine.stats p99
```

Separar si es posible por:

- iOS / Android;
- clase de dispositivo;
- tamaño del ledger.

---

# 39. Decisión final

La lógica inmediata queda:

```text
Ledger / Evidence
      │
      ├── ledgerRevision
      │
      ▼
SkillCatalogIndex
      │
      ▼
MasteryEngine
      │
      ▼
Raw Mastery cache
      │
      ▼
Decay(now)
      │
      ▼
Effective Mastery
```

Preparado para evolucionar a:

```text
LocalMasterySnapshot
+
Dirty Skills
```

Y más adelante, cuando exista una necesidad real:

```text
Server Mastery Snapshot
```

El backend queda por tanto **diseñado pero no prioritario**.

La prioridad actual es mantener un sistema simple, determinista y reconstruible, introduciendo optimizaciones únicamente cuando aporten una mejora real y medible.
