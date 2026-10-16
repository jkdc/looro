# Design: t05-sync-lifecycle-recovery

## Decision

Mantener una fuente de verdad explícita por etapa: el recibo inmutable prueba qué
creación fue aceptada; la revisión del intento gobierna eventos posteriores; el
conflicto conserva ambas ramas; y la bifurcación captura la rama local actual de
forma atómica.

## 1. Repetición segura de la creación inicial

La API almacenará en la misma transacción que crea el intento un recibo inmutable
por `(account_id, attempt_id)`. Contendrá una huella del payload canónico de
creación y los campos necesarios para reproducir el ACK original de revisión 1.
La huella no se devuelve ni se escribe en logs; el recibo queda sujeto al mismo
propietario y ciclo de borrado que el intento.

```text
Device A                    API / PostgreSQL                     Device B
   | create attempt rev 1         |                                  |
   |---------------------------->| insert attempt + receipt, commit |
   |        ACK rev 1 perdido     |                                  |
   |                              |<--------- event base 1, rev 2 ----|
   |                              | apply rev 2, commit -------------->
   | retry identical create      |                                  |
   |---------------------------->| replay original ACK rev 1          |
   | event base 1, rev 2         |                                  |
   |---------------------------->| CAS conflict; preserve rev 2       |
```

Un reintento idéntico reproduce el ACK original, no el estado actual. Un payload
diferente con la misma cuenta e ID se rechaza. La API nunca sustituye el intento
actual a partir de un recibo. Los eventos atrasados continúan usando el conflicto
CAS existente.

## 2. Fechas monotónicas

Al guardar una respuesta o resultado, el cliente persistirá junto con el outbox una
fecha efectiva no anterior a `started_at` ni a la última fecha efectiva del
intento. El valor calculado queda congelado en el evento para que los reintentos
conserven el mismo payload.

La API validará fechas y normalizará el valor persistido como el máximo entre la
fecha recibida, `started_at` y la fecha persistida anterior. Esta defensa de
servidor protege también frente a clientes antiguos o relojes desajustados. La
normalización del estado no cambia el hash del payload original almacenado en el
libro de eventos; el replay del mismo ID sigue devolviendo el ACK ya registrado.

## 3. Aislamiento de conflictos en el drenaje

Android procesará eventos en orden por intento. Al confirmar un conflicto,
persistirá la rama remota, bloqueará el resto de eventos de ese intento durante
ese drenaje y continuará con otros intentos del mismo propietario. Las consultas
posteriores no reenviarán eventos de intentos con conflicto sin resolver. Los
eventos locales permanecen guardados hasta takeover o fork explícito. Errores de
transporte no se reclasifican como conflictos.

No se añade un drenador iOS en este cambio: el contrato de DAO se mantiene probado
en ambos lenguajes, pero el drenaje de producción iOS pertenece a la fase de UI y
sincronización iOS correspondiente.

## 4. Bifurcación de la rama local vigente

La operación de fork leerá en una transacción el intento local actual, respuestas,
posición, resultado y snapshot inmutable. Creará el nuevo ID y su evento inicial
con esa versión, y solo entonces marcará la rama fuente como bifurcada y sus
eventos cubiertos como sustituidos. Un fallo revierte todo. Las mutaciones
posteriores sobre la rama fuente bifurcada se rechazan; el nuevo ID pasa a ser la
rama editable. El intento fuente se conserva para historial local.

## Data and migration

- Añadir una migración PostgreSQL aditiva para el recibo de creación. Antes de
  fijar SQL y restricciones, comprobar las claves y cascadas reales de la tabla de
  intentos; la huella nunca sustituye a la verificación de propietario.
- Mantener el esquema SQLite existente si sus estados actuales representan ya
  `conflict` y `forked`; si no, ampliar de forma aditiva sus valores permitidos y
  reflejarlos en ambos DAOs.
- No reescribir eventos ya almacenados ni borrar intentos/conflictos al migrar.
- Mantener separados el ACK de creación, la revisión de evento y la revisión
  actual del servidor.

## Failure handling

- Una repetición con huella distinta falla de forma explícita y no cambia filas.
- Una transacción interrumpida no deja intento sin recibo, ni recibo sin intento.
- Un conflicto no resuelto conserva los eventos locales y la rama remota.
- Un fork fallido deja intactas la rama fuente y su cola.
- Una sesión/cuenta diferente nunca puede leer, ACKear ni mutar el intento.

## Verification

- API: pruebas unitarias de idempotencia/normalización y pruebas PostgreSQL de
  creación repetida, carrera de revisiones, rollback, propietario y borrado.
- Android: regresiones JVM de persistencia del timestamp, aislamiento por intento,
  conflicto repetido, fork de la última respuesta y rollback transaccional.
- Swift: añadir regresiones espejo de DAO, conservando la limitación: no compilar ni
  ejecutar Swift en este host Linux.
- Ejecutar los comandos de compilación y prueba del proyecto dentro de Docker.
- Tras implementar, ejecutar la suite API aplicable, la suite JVM Android completa
  y los checkers de esquema/exportación pertinentes. Reportar Swift como pendiente.
