# Verify Report: multilanguage-parity

Cierre del marcador F5 (y marcadores residuales F6/F7/F9) por
verificación de contenido ya shipped — sesión 2026-10-02, sin autoría
nueva y sin regeneración (exportadores solo en `--check`).

## F5 — A1 ola 3 (transporte, hobbies, salud, tiendas)

- El contenido no llegó en un commit "ola 3" discreto: la ola
  transporte + salud ×5 aterrizó en `d098876` y los units/lecciones
  restantes (ocio, celebraciones, cierres de escalera) en las olas de
  relleno `a88f757`, `abf2379` y `1a5a9a2` (+ reorden `ab48a07`). El
  marcador quedó `[ ]`; este pase lo cierra tras recontar.
- **A1 = 14 unidades / 40 lecciones en los SEIS foundations**
  (en/es/fr/de/it/pt), recontado de `shared/courses/*-foundations.json`.
- **Listenings A1**: en 15, resto 16 (objetivo ≥15). Escalera no-EN
  16/12/7/7/5 (fr B1=8) supera los objetivos 15/12/7/7/3; en
  15/12/7/7/3 exacto en objetivos.
- **Temas F5 presentes en los 5 no-EN**:
  - transporte: Unterwegs / En movimiento / En mouvement /
    In movimento / Em movimento (billete/fahrkarte/passagem en las 3
    lecciones de cada uno).
  - tiendas: Einkaufen / En el mercado / Au marché / Al mercato /
    Na feira — reforzado por ropa y tallas ×5 (tienda, probador, precio).
  - salud: Ich fühle mich gut / Me siento bien / Je me sens bien /
    Mi sento bene / Me sinto bem (farmacia + vida sana en las 3 lecciones).
  - hobbies-leisure: `vocab:hobbies` taggeado en los 5 idiomas (de u13
    "Was ich gern mache" + u27 "Freizeit"; es u14, fr u13, it u13,
    pt u13 "lo que me gusta") + celebraciones/ocio (Wir feiern /
    Celebra / On fête / Festa! · Al bar / Hora de festejar).

## F6/F7 — cierre del relleno (recontado)

- Forma no-EN = en: 40/18/14/5/5 lecciones por nivel (B1 11 + 3 del
  work-track = 14), listenings 12 A2 / 7 B1 / 7 B2 / 5 C1, readings 4
  A2, checkpoints a2 y b1 presentes en los 5. Cerrados por Verify F6a +
  Verify F10 en tasks.md; los conteos de hoy los reconfirman.

## F9 — pronunciación (residual resuelto)

- La excepción declarada en Verify F10 ya no existe: el módulo
  F2-F5 completo shipped en `f2ba4c7` (2026-10-02) con su propio SDD
  `openspec/changes/pronunciation-module/` (tasks [x], verify-report,
  Android 502/502).

## Gates (2026-10-02, solo lectura)

- `content_health.py` y `content_health.py --parity`: "no issues
  found" ✓ (PARITY_GAP = 0).
- Exportadores `--check` ×5 in sync: courses / listening / readings /
  assessments / android_content.
- Catálogo: 16 cursos, 280 listenings, 122 readings, 30 checkpoints,
  106 skills; cobertura de skills completa (pron 8/8).
- Gradle NO re-ejecutado: `shared/` sin cambios desde `88de326`
  (verde 494/494 de esta mañana); verificación de conteos hecha sobre
  el JSON canónico y los exportadores.

## Notas de proceso

- El marcador F5 era un marcador huérfano: el trabajo existía y estaba
  verificado (F10), pero nadie repasó la casilla. Cerrar marcadores
  desde la evidencia (y no desde la intención) es lo que arregla este
  pase; lo mismo aplicó a F6/F7 `[~]` y F9 `[~]`.
- Única asimetría declarada: en lleva 2 listenings menos que los demás
  en C1 (3 vs 5) y 1 menos en A1 (15 vs 16) — cumple sus objetivos
  exactos; el excedente no-EN es superación, no déficit.
