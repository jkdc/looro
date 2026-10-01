# Verify Report: pronunciation-module

## F2 — Scoring engine + capture (fa62371)

- `PronunciationEngine` (kt+swift): WER determinista sobre el normalizador
  de ExerciseEngine; Levenshtein por palabras (una palabra de más cuesta
  lo mismo que una de menos); umbral de pase 50.
- Captura Android: 4 s de MicRecorder → trimSilence → el mismo WhisperStt
  de Talk; cualquier fallo degrada a escuchar-y-repetir con aviso.
- LearnScreen: botón Check por frase objetivo, puntuación inline y
  feedback pase/reintento; strings EN/ES en paridad (9 claves).
- Tests: PronunciationEngineTest (6 casos) + espejo Swift.

## F3 — Ledger

- Kind `pronunciation` en ProgressEngine kt+swift, PROGRESS_KINDS del
  server, migración 034 (rebuild de constraint, patrón 033) y pin test
  actualizado (progress.test.ts: pass).

## F4 — Mastery

- PRONUNCIATION_WEIGHT 0.8; la evidencia aterriza SOLO en skills pron: de
  la lección (guard contra skills gramaticales co-taggged).
- SkillCatalogIndex `includePronunciation`: flag OFF saca las skills pron
  de taughtSkills — mastery las excluye por completo. NOTA: el commit F1
  original dejaba F1.4 sin implementar; este ciclo lo completó.
- MASTERY_VERSION 2 (invalida la caché). Tests: evidencia solo-pron y
  filtro del índice.

## F5 — Contenido

- Las 5 skills pron sin contenido taggeadas en lecciones inglesas reales
  (th→this/that, sentence-stress→imperativos, connected-speech→small talk,
  short-long-vowels→cocina, r→was/were). Cobertura pron 8/8; Content
  Health "no issues found" (cero avisos por primera vez).

## Gates

- Android **502/502** · server progress tests pass · cross-platform green ·
  python 101/101 · audit_languages 0 hallazgos · exportadores --check ×4.
- iOS espejado (motor, ledger, mastery, flag, toggle Settings, drill con
  SFSpeechRecognizer + AVAudioRecorder a archivo, tests) con proyecto
  Xcode regenerado — compilación pendiente del gate macOS (política repo).

## Notas de proceso

- El build cazó 3 errores antes de los tests: tipo de la caché del índice
  (Pair<String,Boolean>), import faltante en LearnScreen y tests añadidos
  fuera de la clase; además `buildIndex` no reenviaba el flag — lo pilló
  el test del filtro, no el ojo.
