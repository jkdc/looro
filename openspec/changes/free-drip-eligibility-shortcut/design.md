# Diseño: exclusiones positivas de drip en una lectura

Base `aa51066`; rama `feat/api-performance-hardening`. Continuación solicitada tras el tradeoff documentado; se mantiene el método directo elegido.

## Decisión técnica
Leer configuración fresca como antes. Si está desactivada o la cantidad es0, retornar0. Para el resto, una consulta CASE/EXISTS evalúa, en este orden: grant gratuito de cuenta/periodo UTC existente; cuenta guest; suscripción trialing/active. Cualquiera es evidencia positiva para no conceder y permite retornar0 sin transacción.

Si ninguna condición se demuestra, conservar el fallback transaccional completo, incluidas comprobaciones repetidas de guest/suscripción, presupuesto e INSERT ON CONFLICT. No confiar en ausencia de filas para autorizar una escritura. Cuenta ausente, errores SQL, cambios UTC, retirada de marcador y configuración fresca conservan su comportamiento.

Los guards de guest/suscripción pueden observarse antes que en el código previo; solo se adelanta una negativa, nunca una concesión. Cambios concurrentes de elegibilidad siguen revalidados en el fallback si se llega a escribir. No se garantiza conceder por una transición que ocurra después de una negativa observada; el drip sigue siendo oportunista.

## Alternativas y coste
Mantener el lookup anterior dejaba una consulta adicional en los misses. Cachear estado introduce invalidez. Consolidar lecturas reduce roundtrips y evita transacciones sin efecto sin cachear ni conceder fuera de la transacción. El primer candidato libre no reclamado sigue pagando la lectura previa y ahora sus subplanes: medir el coste de ese perfil antes de extrapolar a altas masivas de cuentas.

CASE debe cortocircuitar en el marker gratuito existente; EXPLAIN ANALYZE con datos sintéticos comprobará el índice existente y subplanes no ejecutados. No añadir un índice duplicado. Los estados canceled/past_due no son exclusiones nuevas.

## Verificación
RED para guest/trialing/active sin transacción y persistencia idéntica. Preservar los11 casos anteriores más past_due. La barrera de concurrencia del test observará adquisición de transacción, no un prefijo de SQL. PostgreSQL real, suite completa, comparación antes/después por perfil e independiente final. Sin promesa de mayor RPS ni despliegue.
