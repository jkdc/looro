# Proposal: t05-sync-lifecycle-recovery

## Intent

Cerrar cuatro defectos de ciclo de vida encontrados en la revisión de T05: el
reintento de creación después de perder el ACK inicial, las marcas de tiempo bajo
retroceso del reloj, el bloqueo de otros intentos por un conflicto y la pérdida de
ediciones locales posteriores a la captura del conflicto.

La solución mantiene la sincronización por eventos con revisión CAS. No introduce
mezcla automática ni permite que un reintento de creación sobrescriba el estado
confirmado más reciente del servidor.

## Problem

La creación inicial usa un payload congelado para poder repetirse tras una pérdida
de ACK. Sin embargo, el mismo intento del servidor puede cambiar después; comparar
el reintento con el JSON mutable del intento hace que la creación original no pueda
recuperar su ACK. Por separado, el reloj local puede producir una fecha anterior al
inicio, los conflictos detienen el drenaje completo de Android y una bifurcación
puede copiar una captura local anterior a las últimas respuestas.

## Scope

- Registrar una prueba inmutable de la creación inicial por cuenta e intento, y
  reproducir su ACK original cuando el payload de creación es idéntico.
- Mantener `updated_at >= started_at` y timestamps no decrecientes al guardar y
  sincronizar eventos, sin cambiar los bytes de eventos ya encolados.
- Aislar el bloqueo y reintento de conflictos al intento afectado, dejando avanzar
  los demás intentos del mismo propietario.
- Crear una bifurcación desde la rama local vigente en una transacción y evitar
  nuevas ediciones sobre la rama de origen ya bifurcada.
- Añadir regresiones API/PostgreSQL, Android JVM y pruebas de DAO Swift.

## Out of scope

- No cambiar la política de propietario, CAS, takeover explícito ni resultados
  inmutables.
- No añadir un drenador de producción iOS en esta corrección; la implementación de
  ese ciclo permanece en su tarea iOS posterior.
- No ejecutar ni declarar Swift verificado en Linux.
- No reescribir commits, hacer push, crear PR ni integrar en `main`.
- No cambiar la composición ni la calificación de exámenes.

## Delivery and containment

- Mantener el trabajo en `feat/weekly-exam-recovery`; la integración futura sigue
  dirigida a `feature/learning-platform`, nunca a `main`.
- Toda compilación y prueba de proyecto se ejecuta en Docker. Gentle AI se ejecuta
  fuera de Docker.
- La migración del recibo de creación será aditiva, conservará intentos existentes
  y se eliminará en cascada solo al borrar el intento propietario. El rollback
  recomendado es de código hacia delante: no borrar recibos ni historia para
  recuperar una versión anterior.
- La aceptación de esta propuesta no equivale a aprobación de entrega ni de merge.
