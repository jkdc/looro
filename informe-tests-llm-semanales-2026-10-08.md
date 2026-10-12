# Informe: generación semanal de tests con LLM (level-tests v3) — 2026-10-08 (rev. 2)

Propuesta de Joaquín, cerrada el mismo día: el backend **genera** cada semana
tests nuevos por nivel e idioma (A1–B1, EN+ES) con un **modelo de examen**
estructurado y un **prompt definido** contra un **LLM seleccionable**
(OpenAI / GLM 4.5 / GLM 5.3 / OpenCode, desde panel y `.env`), guarda
**varias variantes por test** para que distintos usuarios no reciban las
mismas preguntas, y expone **endpoints separados** (LLM-only con excepción si
no hay datos, determinista actual intacta, e híbrido). Un cron semanal corre
la generación. Sin lógica anti-repetición histórica (coste creciente
descartado por decisión expresa). Este informe es la guía de implementación:
qué hay que hacer y cómo.

## 1. Decisiones cerradas (2026-10-08, Joaquín)

1. **Endpoints separados** (§8): determinista actual intacto; nuevo
   **LLM-only** que lanza 404 si la semana no existe; y **híbrido** que cae
   al determinista. Ningún cambio de contrato en el actual.
2. **Publicación**: cola de revisión manual Y auto-publicación, **ambas
   seleccionables** — por env var ahora (`LEVELTEST_PUBLISH_MODE=review|auto`,
   default `review`) y desde el panel de administración después.
3. **Proveedor LLM**: abstracción multi-proveedor seleccionable desde panel y
   `.env`. Incluye **OpenAI**, **GLM 4.5 y GLM 5.3** (Zhipu, OpenAI-compatible)
   y **OpenCode** (gateway OpenAI-compatible, URL configurable). El panel
   muestra un desplegable de proveedores, otro de modelos configurados y un
   **input de API key que se guarda en BD cifrada** (§5).
4. **Alcance**: mínimo viable — **A1–B1, EN y ES**. (B2/C1 y más idiomas,
   fuera por ahora; el diseño no los impide.)
5. **Sin anti-repetición histórica** (preocupación de coste creciente).
   Variedad por **sobre-generación**: el LLM produce un pool más grande y el
   servidor compone **K variantes distintas del mismo test** (§7); distintos
   usuarios reciben distintas variantes (solapamiento parcial asumido y
   buscado). La validación solo deduplica **dentro** de la misma generación
   (coste cero).
6. **App**: sin cambios por ahora (no consume los endpoints nuevos).
7. **Validación en tres puertas** (añadida tras discusión del 2026-10-08):
   validador TS determinista → **juez LLM con rúbrica** (flag/strict) →
   revisión humana. Ninguna sustituye a la otra; detalles en §6.

## 2. Qué ya existe y se reutiliza (verificado)

| Pieza | Dónde | Reuso en v3 |
|---|---|---|
| Compositor determinista + golden Kotlin↔TS | `src/level-tests.ts` | Intacto (endpoint actual) y **motor de variantes** (§7) |
| Endpoint `GET /v1/level-tests/current` (ETag/304, 404/400) | `src/app.ts` | Intacto; hermanos `generated`/`hybrid` |
| Llamada LLM interna del server | `OpenAIHostedResponses` (`hosted-responses-transport.ts:14`) | Patrón del adaptador OpenAI |
| Endpoint OpenAI-compatible ya conocido | GLM: `https://api.z.ai/api/paas/v4/` (usado por el motor Android) | Adaptador GLM |
| Validación de bancos | `scripts/export_leveltests.py` | Reglas portadas a TS (`level-tests-validate.ts`) |
| Ítems autorizados con `explain` | `shared/leveltests/*.json` (1060) | Few-shot del prompt |
| CLI operador prepare/apply + auditoría | `minutes-admin.ts`, `tokens-admin.ts` | CLI generar/aprobar/retirar |
| Panel admin + `admin_actions` + browse | `admin-auth.ts`, `admin-browse.ts` | UI de config LLM y semanas |
| Workers periódicos | `main.ts:125` | Opción B del cron |
| Migraciones + triggers de inmutabilidad | `migrations/` | Tablas nuevas |

## 3. Arquitectura

```
Cron (lunes 04:00 UTC, configurable)
 └─ npm run leveltests-generate [--week auto] [--force]
     por cada idioma(en,es) × nivel(A1,A2,B1):
       1. plantilla de examen (§4) + few-shot del nivel + tema semanal
       2. LLM (proveedor/modelo/key: config BD cifrada > env; §5)
          → pool SOBREDIMENSIONADO (2× secciones)
       3. PUERTA 1 valida (§6): schema, claves, opciones, disjunción,
          deduplicación solo interna; reintento dirigido por sección
       4. PUERTA 2 juez LLM (§6): rúbrica semántica por ítem
          (ok|flagged|reject) — flag anota, strict reintenta/rechaza
       5. compone K=3 variantes deterministas del pool (§7)
       6. guarda level_test_weeks (draft; metadata modelo/prompt/juez/coste)
     modo review → espera approve (CLI/panel, con los flagged anotados);
     modo auto → aprueba solo si TODO validó (juez en strict recomendado)
 Endpoints (§8): current (determinista, igual que hoy) · generated
   (LLM, 404 si no hay) · hybrid (LLM → determinista)
```

Fail-closed: proveedor sin configurar, LLM caído o validación fallida ⇒ esa
semana no hay `generated` (404) y `hybrid` sirve el determinista. Nunca hay
semana sin test en `hybrid`/`current`.

## 4. Modelo de examen (plantilla versionada)

`shared/leveltests/exam-models.json` (contenido como código, revisable en PR;
el validador y el prompt lo consumen):

```json
{ "version": 1, "levels": [ {
  "level": "B1", "languageID": "*",
  "poolTarget": {                       // 2× el examen → variantes
    "recognition": 24, "writing": 6, "readingPassages": 4, "listeningPassages": 4 },
  "examShape": { "recognition": 12, "writing": 3,
    "readingPassages": 2, "readingPerPassage": 3,
    "listeningPassages": 2, "listeningPerPassage": 2 },
  "sections": {
    "recognition": { "mix": { "choice": 16, "gap": 8 },
      "skills": ["opiniones", "condicionales", "conectores", "pasado narrativo"],
      "options": 4, "explain": true },
    "reading":      { "words": [60, 110], "questions": 3, "registro": "cotidiano/laboral" },
    "listening":    { "words": [40, 80], "questions": 2, "formato": "diálogo A-B" },
    "writing":      { "variants": 2, "promptInInstructionLanguage": true } },
  "canDoAnchor": "Puede dar opiniones y desenvolverse viajando",
  "forbidden": ["reutilizar un pasaje en lectura y listening", "registro C1", "temas ofensivos/políticos/marcas"]
} ] }
```

## 5. Proveedores LLM y configuración (panel + env, claves cifradas en BD)

**Tabla `llm_provider_configs`** (migración nueva):

```sql
CREATE TABLE llm_provider_configs (
  id uuid PRIMARY KEY,
  provider text NOT NULL CHECK (provider IN ('openai','glm','opencode')),
  label text NOT NULL,                  -- "GLM 5.3 Flash (producción)"
  base_url text NOT NULL,               -- openai: api.openai.com/v1 · glm: api.z.ai/api/paas/v4 · opencode: URL del gateway
  model text NOT NULL,                  -- gpt-5.6-luna · glm-4.5-flash · glm-5.3-flash · modelos opencode
  api_key_encrypted bytea NOT NULL,     -- AES-256-GCM
  is_active boolean NOT NULL DEFAULT false,   -- exactamente una activa (trigger)
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (is_active OR true)             -- la restricción "una activa" la impone un trigger
);
```

- **Cifrado**: AES-256-GCM con clave maestra `LEVELTEST_LLM_MASTER_KEY`
  (32 bytes hex, solo env). Sin clave maestra, las keys de BD no se pueden
  descifrar y el sistema **cae a la config de env** (fail-closed, nunca
  guarda en claro). La API de admin **nunca devuelve la key** (solo hint
  `sk-…abcd`); toda mutación escribe `admin_actions`.
- **Resolución de config** (una función, un punto):
  `active(llm_provider_configs) → env (LEVELTEST_LLM_PROVIDER/MODEL/URL/KEY)
  → error limpio`. El env documenta los defaults:
  ```
  LEVELTEST_LLM_PROVIDER=openai|glm|opencode
  LEVELTEST_LLM_MODEL=gpt-5.6-luna|glm-4.5-flash|glm-5.3-flash|…
  LEVELTEST_LLM_URL=…      # opcional, default por proveedor
  LEVELTEST_LLM_API_KEY=…
  LEVELTEST_LLM_MASTER_KEY=<32-byte hex>   # cifra las keys del panel
  LEVELTEST_PUBLISH_MODE=review|auto
  LEVELTEST_VERIFY=flag|strict             # puerta 2: juez LLM (§6)
  ```
- **Panel** (fase F4): página "Generación LLM": desplegable de proveedor,
  desplegable de modelos (los `llm_provider_configs` + los conocidos por
  defecto por proveedor), input de API key, test de conexión ("probar" hace
  una llamada mínima y devuelve ok/error sin exponer la key), y selector de
  modo de publicación. Endpoints admin: `GET/PUT /v1/admin/llm-config`,
  `POST /v1/admin/llm-config/test`, con CSRF como el resto del panel.
- **Adaptadores**: un transporte único OpenAI-compatible
  (`chat/completions`, JSON mode si existe) + 3 presets de URL/modelos.
  OpenCode entra como preset con `base_url` configurable (confirmar la URL
  del gateway al configurarlo — nota de implementación, no de diseño).

## 6. Prompt y validación — tres puertas en serie

Cada semana generada pasa por tres puertas; ninguna sustituye a la otra
(decisión de Joaquín 2026-10-08: puerta 2 añadida).

**Puerta 1 — Validador TS determinista** (`src/level-tests-validate.ts`,
reglas portadas del exporter + nuevas): schema exacto; ids únicos prefijo
`llm-<lang>-<level>-w<week>-…`; choice con 4 opciones sin duplicados y
exactamente 1 correcta entre ellas; written con 1–3 respuestas; `explain`
presente; pasajes en rango de palabras y **lectura/listening disjuntos**;
idioma correcto del contenido (heurística stopwords); **deduplicación de
enunciados SOLO dentro de la generación** (similitud normalizada estilo
`ExerciseEngine`); conteos de pool objetivo. Todo lo mecánico se valida
aquí — determinista, gratis, testeable en CI; nada de esto pasa por LLM.
Fallo de sección ⇒ 1 reintento dirigido de esa sección con el error como
feedback; fallo del examen ⇒ descarte del nivel esa semana (fallback).

**Puerta 2 — Juez LLM (validación semántica y pedagógica)**: lo que la
puerta 1 no puede ver — si la clave "correcta" lo es de verdad, si algún
distractor es también defendible, si el ítem es genuinamente del nivel CEFR
o si el `explain` enseña bien la regla — lo revisa una segunda pasada
LLM-as-judge que reutiliza la abstracción de proveedores (§5) con otro
prompt: una **rúbrica de verificación** que recibe el examen ya validado
mecánicamente + la plantilla del nivel y emite veredicto **por ítem**
(`ok | flagged | reject`) con motivo en una frase. Modos:

- `LEVELTEST_VERIFY=flag` (default con publicación `review`): los ítems
  marcados quedan anotados en el draft (`payload.review[]`) — el revisor
  humano entra con el trabajo previo hecho y solo mira lo marcado.
- `LEVELTEST_VERIFY=strict` (recomendado con publicación `auto`): los
  `reject` se eliminan y los `flagged` fuerzan reintento dirigido de esa
  sección; el juez es puerta dura.

La rúbrica se versiona junto al prompt (`judge_version`, misma tabla) para
reproducibilidad y auditoría. Límites honestos: el juez reduce mucho los
falsos negativos (la clave errónea es el fallo nº1 de los LLMs escribiendo
exámenes) pero no los elimina, y puede dar algún falso positivo — por eso
existe `flag`, y la revisión humana sigue siendo el patrón oro. Coste ≈ 1
llamada extra por nivel y semana (lee el examen completo): ~2× el coste de
generación, irrelevante a 6 exámenes/semana.

**Puerta 3 — Revisión humana** (solo en modo `review`): aprueba con los
ítems ya filtrados por 1–2. En modo `auto` no existe; el riesgo residual
(aceptado explícitamente al activar auto) queda mitigado por 1 + 2 strict.

**Prompt de generación** (versión 1, se guarda `prompt_version` con cada
generación): sistema ("redactor de exámenes CEFR {level} de {idioma}; SOLO
JSON conforme al schema; una respuesta correcta indiscutible y 3
distractores plausibles pero claramente incorrectos; `explain` enseña la
regla en una frase; nada ofensivo/político/marcas") + usuario (plantilla
del nivel + **6 few-shot cosechados del nivel** + tema rotativo de la
semana + tamaños de pool objetivo). Temperatura 0.4, JSON mode. **Sin
consultas históricas en ninguna puerta** — coste plano semana a semana
(decisión 1.5).

## 7. Variantes (variedad entre usuarios, coste cero extra)

El LLM genera **una vez** un pool 2× por nivel; el servidor compone **K=3
variantes** con el mismo mecanismo determinista ya existente: orden estable
`sha256(promptVersion|SECTION|variantIndex|id)` + bloques — variante k toma
el bloque desplazado k posiciones. Propiedades: dos variantes comparten
~50% de ítems (solapamiento asumido), lectura/listening siguen disjuntos
**dentro de cada variante** (la selección de pasajes también se desplaza por
variante), y la composición es verificable en test (misma semilla ⇒ mismas
variantes). El endpoint entrega una variante: `?variant=1..3` explícito o
**elección aleatoria por petición** (usuario distinto ⇒ variante distinta en
general). ETag incluye el id de variante (§8).

## 8. Endpoints (separados, como pediste)

| Ruta | Fuente | Semana LLM inexistente |
|---|---|---|
| `GET /v1/level-tests/current` (hoy, intacto) | determinista cosechado | — (sigue igual) |
| `GET /v1/level-tests/generated` | LLM aprobado de la semana ISO | **404 `level_test_not_generated`** |
| `GET /v1/level-tests/hybrid` | LLM aprobado; si no | determinista (actual) |

- Parámetros comunes `?language=&level=` (400 si faltan; 404
  `level_test_unavailable` para niveles sin pool, p. ej. B2/C1).
- `generated`/`hybrid` añaden `?variant=1..K` (default: aleatoria) y
  responden el **mismo contrato** de examen compuesto + `weekId`,
  `expiresAt`, `variant`, `source: "llm"|"deterministic"` (hybrid lo dice;
  la app futura lo puede ignorar). ETag = hash(payload+variante) ⇒ 304
  correcto por variante.
- Tests: precedencias, 404 generado, fallback híbrido, variantes distintas y
  reproducibles, ETag/304 por variante, retirada de semana (`retired`).

## 9. Almacenamiento

```sql
CREATE TABLE level_test_weeks (
  id uuid PRIMARY KEY,
  language_id text NOT NULL, level text NOT NULL,
  week_index int NOT NULL,                       -- ISO yyyynn
  source text NOT NULL DEFAULT 'llm',
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','approved','retired')),
  pool jsonb NOT NULL,                           -- salida validada del LLM
  variants jsonb NOT NULL,                       -- K exámenes compuestos
  generator text NOT NULL, model text NOT NULL,
  prompt_version int NOT NULL, judge_version int,
  tokens_in bigint, tokens_out bigint, judge_tokens bigint,
  review jsonb,                             -- veredictos del juez (flagged/reject + motivos)
  created_at timestamptz NOT NULL DEFAULT now(),
  approved_at timestamptz, approved_by text,
  UNIQUE (language_id, level, week_index)
);
```

Inmutable una vez `approved` (trigger existente); `retired` retira sin
borrar auditoría; visible en `admin-browse` + métricas de coste semana a
semana. Modo de publicación: `level_test_settings` singleton
(`publish_mode`) editable por el panel, con env como default.

## 10. Cron

- **Opción A (recomendada)**: CLI `npm run leveltests-generate` + timer del
  host (`0 4 * * 1`). Re-run = reintento; log de proceso limpio.
- **Opción B**: scheduler in-process estilo `main.ts` con
  `pg_try_advisory_lock` si el despliegue no tiene cron.
- Idempotente por `UNIQUE(language,level,week)`; `--force` regenera drafts.
- Coste acotado y plano: 6 generaciones/semana (+ reintentos puntuales) con
  pool 2×; tokens registrados por semana.

## 11. Plan de fases (qué hay que hacer y cómo)

| Fase | Contenido | Verificación |
|---|---|---|
| **F0 — Proveedor + config** | Transporte OpenAI-compatible único + presets openai/glm/opencode; tabla `llm_provider_configs` + cifrado AES-GCM con clave maestra env; resolución config BD>env; `LEVELTEST_*` en `.env.example` | Tests unitarios con stub HTTP (elegir proveedor, descifrar, fail-closed sin master key); cifrado round-trip |
| **F1 — Plantilla + prompts + validador** | `exam-models.json` (A1–B1 ×2 idiomas); prompt de generación v1 con few-shot; **rúbrica del juez v1**; `level-tests-validate.ts` (puerta 1) | Fixtures buenos/malos (clave duplicada, opción incorrecta, pasaje repetido, idioma erróneo, conteos); reintento dirigido con stub; parseo de veredictos del juez con fixtures |
| **F2 — Generación + juez + variantes** | CLI `leveltests-generate` (usa F0; puerta 1 de F1; **puerta 2 juez** con `LEVELTEST_VERIFY=flag|strict`; compone K=3 variantes con el motor existente; guarda draft con metadata y veredictos); approve/retire (CLI y luego panel) según `publish_mode` | Generación + juez reales en local contra GLM/OpenAI con clave de prueba + suite completa con stubs deterministas (juez marca → flag anota, strict reintenta); variantes reproducibles |
| **F3 — Endpoints** | `generated` (404) y `hybrid` con `?variant=`, ETag por variante, `source`; migración + precedencia | Suite server completa (PG efímero): matriz de rutas/estados, ETag/304, retirada |
| **F4 — Panel + cron** | Página admin (proveedor/modelo/key cifrada/test de conexión/modo); endpoints admin con CSRF y `admin_actions`; cron A o B + runbook + alarma de fallo | Flujo manual completo en local; simulación de semana fallida → híbrido sirve determinista |
| **F5 — Futuro (no ahora)** | B2/C1 y más idiomas por LLM; consumo desde la app (`generated`/`hybrid` ya listos); auto-publicación por defecto si la calidad lo permite | — |

## 12. Riesgos y mitigaciones

- **Claves de API en BD**: AES-256-GCM con maestra solo en env; nunca se
  devuelven (hint enmascarado); toda mutación audita; logs sin keys (regla
  ya del repo).
- **Calidad LLM** (claves erróneas, ambigüedad, deriva de dificultad):
  puerta 1 determinista + **puerta 2 juez** + modo revisión por defecto +
  few-shot del nivel + métrica semanal de coste y (futuro) de acierto.
- **Juez imperfecto**: no elimina todos los falsos negativos y puede dar
  falsos positivos — modos flag/strict separan "ayudar al revisor" de
  "bloquear"; en auto, strict + puerta 1 es el máximo achievable sin
  humano (riesgo residual aceptado explícitamente).
- **Variedad sin anti-repetición**: repetición entre semanas posible
  (aceptada); dentro de una semana, la sobre-generación + variantes cumple
  el objetivo (usuarios ≠ preguntas); el tema semanal rotativo del prompt
  diversifica estructuralmente sin coste.
- **ETag con variante aleatoria**: cada respuesta lleva su ETag; una
  revalidación con `If-None-Match` puede tocar otra variante y recibir 200
  con otro ETag — correcto HTTP, coste de bytes aceptable; alternativa
  `?variant=` fija para clientes que quieran cacheo perfecto.
- **OpenCode**: entra como preset OpenAI-compatible con URL configurable;
  confirmar gateway/modelos exactos al configurarlo (nota de implementación).

## 13. Pendientes menores (defaults que puedo ajustar si los quieres cambiar)

- K=3 variantes por defecto (configurable por env `LEVELTEST_VARIANTS`).
- Juez: `LEVELTEST_VERIFY=flag` por defecto (con review) y `strict`
  recomendado al activar auto — configurable también desde el panel (F4).
- Cron lunes 04:00 UTC (configurable; genera → revisa → publica antes del
  giro de semana).
- Excepción del endpoint LLM-only: 404 `level_test_not_generated`
  (¿prefieres otro código/nombre?).
- Sobre-generación 2× (pool doble del examen) — balance coste/variedad.
