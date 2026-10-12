# Informe: relleno por nivel e idioma (level-tests coverage) — 2026-10-09

Qué falta para que cada idioma tenga weekly tests verificados en cada nivel
A1–C1, con el estado exacto por pieza y el plan por fases. Complementa
`informe-tests-llm-semanales-2026-10-08.md` (v3 LLM, implementado y
verificado) y el change `level-tests-llm`.

## 1. Estado verificado hoy

- **Server suite: 464/464** (Node 20, PG 17 efímero) tras dos fixes de tests
  que la generación real dejó obsoletos (el fallback híbrido ya sirve `llm`
  para es/B1; el caso "missing" usa es/B1 retirado). Android: 664/664.
- **Semanas LLM aprobadas y servidas**: en/es × A1/A2/B1 (6 de 6), con
  3 variantes cada una, generadas por GLM 5.3 Flash y validadas por las
  tres puertas.

## 2. Inventario por idioma (cosecha verificada hoy)

| Idioma | Cosecha A1/A2/B1/B2/C1 | Checkpoints | Placement | Pools det | Weekly LLM |
|---|---|---|---|---|---|
| **en** | 250/144/194/80/38 | A1–C1 | SÍ | A1,A2,B1 | A1,A2,B1 ✓ |
| **es** | 256/142/96/56/47 | A1–C1 | SÍ | A1,A2,B1 | A1,A2,B1 ✓ |
| **fr** | 257/138/102/55/47 | A1–C1 | no | — | — |
| **de** | 255/135/93/55/46 | A1–C1 | no | — | — |
| **it** | 256/136/95/56/50 | A1–C1 | no | — | — |
| **pt** | 254/134/94/55/50 | A1–C1 | no | — | — |
| **nb** | — | — | no | — | — |
| **zh** | — | — | no | — | — |

La cosecha (ejercicios de lección + preguntas de listening) existe de sobra
para **fr/de/it/pt en A1–C1** — el contenido para few-shot y anclas ya está.
**nb y zh no tienen contenido de temario cosechado** (el app los enseña,
pero su temario vive fuera de `shared/courses`).

## 3. Hueco por nivel e idioma (relleno necesario)

### A. Weekly LLM tests (el más barato — solo config + generación)
| Nivel | en | es | fr | de | it | pt | nb | zh |
|---|---|---|---|---|---|---|---|---|
| A1–C1 (8 lang × 5 niveles = 40 semanas/semana) | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |

Relleno: (1) **bug en el prompt**: `buildGenerationMessages` dice "English"
para todo idioma ≠ es — hay que mapear `languageID → nombre de idioma` para
los 8; (2) añadir B2/C1 al `exam-models.json` (la LLM escribe los ítems, no
necesita cosecha); (3) el heurístico de idioma de la puerta 1 solo tiene
marcadores en/es — para fr/de/it/pt/nb/zh pasa por defecto (sin chequeo) o
se añaden marcadores básicos; (4) correr el CLI por idioma.

### B. Deterministic pools (bundled offline de la app)
| Nivel | en | es | fr | de | it | pt |
|---|---|---|---|---|---|---|
| A1–B1 | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ |
| B2/C1 | ✗ (rec 30/36, writ 8/9 en B2; C1 aún menor) | ✗ (24/36) | | | | |

Relleno: (1) relajar mínimos (36→30 recon; writing 9→6/8) — B2 entra en
ambos; C1 sigue corto; (2) o LLM-generar los ítems que faltan con el mismo
pipeline v3 y commitearlos tras revisión. La segunda es la vía del informe
v3 (§7 variantes) y rellena C1 también.

### C. Placement banks (solo en/es)
fr/de/it/pt/nb/zh: el prompt de placement es igualmente plantillable con la
misma puerta juez. 60 ítems por idioma.

### D. nb y zh (sin temario cosechado)
El app los lista pero no tienen courses/listening en shared. Su temario
vive fuera del repo (autoría) — los weekly tests pueden generarse por LLM
(ancla can-do + idioma) pero las lecciones/checkpoints de preparación no
existen. **Recomendación: nb/zh fuera del alcance hasta que el temario
exista** — el endpoint ya responde 404 y la app no muestra entrada.

### E. App consumption + panel
- App: consumir `generated`/`hybrid` cuando `MANAGED_API_ORIGIN` esté
  configurado (hoy vacío en builds personales → las pantallas usan bundled).
- Panel: página de config LLM sobre la API ya implementada.

## 4. Fases

| Fase | Contenido | Verificación |
|---|---|---|
| **FA — Cobertura completa de weekly LLM (todos los idiomas, A1–C1)** | Fix prompt language-name map; B2/C1 en exam-models; marcadores de idioma fr/de/it/pt/nb/zh en la puerta 1; generar 8×5=40 semanas | Suite server verde; 40 semanas aprobadas y servidas; verificación de idioma de contenido por muestra |
| **FB — Placement + pools det para fr/de/it/pt** | Placement banks por LLM (60/idioma) + pools det por LLM-committed tras revisión | Exporter verde; suite; smoke |
| **FC — App consumption** | Android: fetch `hybrid` cuando MANAGED_API_ORIGIN exista; fallback bundled | Tests JVM del parseo; smoke con servidor local |
| **FD — Admin panel page** | Página config LLM sobre la API existente | Manual |
| **FE — Humano** | Revisión de hablante competente de los 19 flagged + los nuevos niveles | Firma del revisor |

## 5. Recomendación de orden

FA y FB son independientes y baratos: FA es ~30 min de generación real;
FB requiere la revisión de hablante antes de commitear pools. FC toca la
app; FD toca el panel. El orden propuesto: **FA → FC → FB → FD → FE**,
aunque FB y FC pueden ir en paralelo.
