# Diseño: atajo positivo de drip gratuito

Base 360 ab 43; rama feat/api-performance-hardening. Autorización: «continua» después de la propuesta y sus pruebas descritas en el diagnóstico CPU/SQL. Se conserva el método directo elegido previamente.

## Decisión y tradeoffs
Mantener lectura fresca de subscription_config. Si drip está desactivado o cantidad 0, devolver 0 sin transacción. Si está activo, buscar una concesión FREE de cuenta y periodo UTC con el mismo índice de expresión existente. Una presencia permite devolver 0; ausencia entra en el recorrido transaccional existente, con todos los guards e INSERT ON CONFLICT intactos. La consulta previa no concede nada ni sustituye exclusión/idempotencia.

La primera concesión añade una consulta; las repetidas evitan BEGIN, comprobaciones de guest/suscripción, INSERT conflict y COMMIT. No cachear: un cambio de política o eliminación controlada de grant se ve en la siguiente llamada. No asumir que subscription_grants es inmutable. Firma pública y respuestas quedan iguales.

Alternativas ya consideradas en el diagnóstico: caché exige invalidación; worker cambia disponibilidad de minutos; más pool/índices no elimina consultas repetidas. No se incluyen.

## Verificación
RED/GREEN de transacción evitada con PostgreSQL real, observando adquisiciones explícitas del helper transaction (no confundidas con conexiones internas de Pool.query), retorno y datos persistidos. Preservar primer grant, carreras de primer intento, rollover UTC, invitados/suscritos, presupuesto global y errores de configuración/consulta. Suite completa, verificador independiente y comparación repetida antes/después sin profiling de CPU.

Sin nuevas garantías de producción ni arreglo de carreras preexistentes fuera de este cambio. No publicar ni desplegar.

## Resultado y coste de misses
El miss añade una lectura también a invitados y suscriptores sin marcador libre, no solo al primer grant. Los controles del benchmark cuantifican guest 9→10 y suscrito 10→11 sentencias por GET. Se conserva como tradeoff explícito del atajo acotado; no se promete mejora universal y se revisará según mezcla real. Evidencia final en verify-report.md.
