# Diseño T07

El diseño completo está en docs/superpowers/specs/2026-10-16-t07-android-exam-recovery-design.md. La especificación normativa es specs/android-exam-recovery/spec.md.

Decisión central: conservar ExamHistoryDao/LearningDbWriter y el FIFO IO de AppViewModel; añadir codec/repositorio para convertir selección bundled/remota a snapshot versionado y reconstruir únicamente desde snapshot almacenado. La remota conserva JSON/identity originales; la bundled usa hash canónico API (objetos ordenados, arrays intactos), verificado al persistir/leer. attemptId es la identidad de reanudación, no semana, nivel ni índice. Ninguna pantalla accede directamente a SQLite ni recompone intentos históricos con el catálogo actual.
