# Diagnóstico CPU/SQL: reducir trabajo repetido antes de escalar

**Fecha:** 30 de octubre de 2026. **Código analizado:** `0577ace`. **Estado:** diagnóstico y propuesta; ningún cambio de runtime implementado en esta tarea.

## 1. Conclusión

La siguiente mejora candidata es **evitar que cada lectura de saldo repita la transacción de concesión gratuita cuando esa concesión mensual ya existe**. No hace falta empezar por Redis, otro índice, un pool mayor o desactivar observabilidad.

Evidencia local con PostgreSQL real y proveedor falso:

- Con OTEL, hubo **24.028 intentos de INSERT de la concesión periódica y solo 140 filas insertadas**. Los otros **23.888 (99, 42%)** acabaron sin insertar por idempotencia.
- La ruta lee además la misma configuración de suscripciones dos veces por lectura de saldo.
- La pila del cliente PostgreSQL aparece en aproximadamente **40, 5% de las muestras ponderadas no-idle**, frente a 4, 5% con pila OTel y 7, 2% con pila del logger. Son atribuciones inclusivas, potencialmente solapadas; no un reparto de CPU del servidor PostgreSQL.
- Los tiempos medios SQL observados de las consultas calientes son bajos, aproximadamente 0, 02–0, 10 ms. No hay aquí evidencia suficiente para justificar un índice nuevo o declarar una consulta individual de escaneo como causa dominante.

**Esto localiza trabajo evitable, pero todavía no demuestra cuánto ganará una corrección.** La mejora requiere pruebas de integridad y comparación antes/después. Se mantienen las conclusiones de capacidad del [informe anterior](informe-capacidad-api-2026-10-30.md); no se eleva ninguna cifra de producción a partir de estos perfiles.

## 2. Experimento

Dos stacks locales independientes con los mismos fixtures y código: OTEL activado y desactivado. En ambos:

- PostgreSQL 17 con pg_stat_statements y track_io_timing, solo para diagnóstico.
- Node con `--cpu-prof`, perfil guardado al apagar limpiamente la API.
- 200 cuentas sintéticas; mezcla 70% saldo autenticado y 30% catálogo/health; 2000 redes sintéticas; máximo 100 peticiones en vuelo.
- Warmup de 5 s a 50/s, seguido de 30 s a 400/s y 30 s a 800/s.
- Estadísticas SQL reiniciadas después de seed/arranque, antes del workload. Incluyen preflight, warmup y los escalones; el perfil CPU incluye también startup y shutdown.
- Sin TLS/Caddy, datos reales, proveedores externos o cuotas CPU/RAM específicas. No se cambió configuración persistente.

Se reutilizó el driver existente. Los harnesses y overrides del experimento son evidencia local ignorada, no nuevos comandos mantenidos ni cambios del producto.

### Resultados con profiling activo

| Variante | Escalón | Útiles/ofrecidas | Útiles/s incluido drenaje | p95 éxito ms | Llegadas no emitidas |
|---|---:|---:|---:|---:|---:|
| OTEL on |400/s |11978/12000 |399, 3 |10, 51 |22 |
| OTEL off |400/s |11976/12000 |399, 2 |9, 21 |24 |
| OTEL on |800/s |22093/24000 |736, 4 |24, 53 |1907 |
| OTEL off |800/s |22096/24000 |736, 5 |10, 61 |1904 |

Las emitidas terminaron con respuestas válidas. **Ninguna variante entregó toda la oferta de 800/s**. Quitar OTel no cambió materialmente la cantidad completada en este par, aunque sí cambió la latencia observada. No es prueba de coste cero: el perfil muestra trabajo OTel, pero no justifica retirar sus señales.

Este resultado tampoco contradice ni reemplaza el escalón previo que entregó 675, 5 útiles/s: el orden de carga, el profiling y la variación local no son idénticos, y solo se ejecutó un par. No se presenta esa diferencia como mejora de código ni como nuevo techo sostenible.

## 3. Dónde está el trabajo

### Ruta de lectura

`services/api/src/app.ts`, handler GET `/v1/minutes`, ejecuta:

1. Autenticación contra PostgreSQL.
2. `runDripForAccount`, cuyo error se trata como oportunista y no bloquea la lectura.
3. Lectura del balance actual.
4. Lectura de suscripción y catálogo para construir la respuesta.

`services/api/src/subscriptions.ts:134–157` hace en cada drip:

1. Leer configuración.
2. Abrir transacción.
3. Comprobar si es guest.
4. Comprobar suscripción activa.
5. Intentar insertar la concesión mensual con ON CONFLICT DO NOTHING.
6. Confirmar transacción, incluso cuando el INSERT no añadió nada.

En este fixture la configuración comprobada es **free_drip_enabled=true y free_drip_minutes=5** (`migrations/027_subscriptions.sql:4–14`), no una concesión desactivada. Cuando la cuenta ya recibió sus minutos del periodo, el INSERT devuelve cero filas y `grantWithin` retorna 0 antes de tocar el wallet.

### Estadísticas OTEL on

| Consulta / operación normalizada | Llamadas | Total ejecución ms | Media ms |
|---|---:|---:|---:|
| Autenticación sesión+cuenta |24029 |2330, 5 |0, 0970 |
| Configuración de suscripciones |48056 |1501, 9 |0, 0313 |
| Balance de minutos |24028 |1306, 0 |0, 0544 |
| INSERT de subscription_grants |24028 |1299, 5 |0, 0541 |
| Comprobación de guest |24028 |642, 2 |0, 0267 |
| Comprobación de suscripción activa para drip |24028 |582, 7 |0, 0242 |
| Lectura de suscripción para respuesta |24028 |548, 3 |0, 0228 |
| Planes para catálogo |24028 |476, 6 |0, 0198 |
| COMMIT |24029 |18, 5 |0, 0008 |
| BEGIN |24029 |17, 3 |0, 0007 |

No sumar esos tiempos como si fueran duración HTTP o CPU de Node: hay concurrencia, red, colas y trabajo del cliente fuera de la ejecución SQL. Las medias tampoco permiten conocer la cola p99 de cada consulta.

El INSERT se ejecutó 24028 veces y devolvió 140 filas; con OTEL off fueron 24030 llamadas y las mismas 140 filas. La diferencia de dos peticiones corresponde al tráfico realmente emitido, no a una regla distinta de concesión. El conjunto caliente ejercita 140 de las 200 cuentas sembradas debido al reparto determinista 70/30; no es una prueba de distribución uniforme sobre todas ellas.

### Muestras CPU

| Atribución inclusiva | OTEL on, segundos de muestras no-idle | Porcentaje aproximado |
|---|---:|---:|
| Pila pg/pg-protocol |14, 939 |40, 5% |
| Pila del logger de aplicación |2, 658 |7, 2% |
| Pila OTel |1, 651 |4, 5% |

Las muestras no-idle ponderadas suman 36, 892 s con OTEL y 33, 162 s sin OTEL. No son lecturas directas de un contador de CPU, ni un A/B repetido que permita prometer un ahorro porcentual. Las categorías pueden solaparse y hay trabajo nativo no atribuido a ellas.

Entre las funciones propias del cliente PG aparece `Result.addFields`: reconstruye metadatos y parsers de cada resultado (`node_modules/pg/lib/result.js:82–105`). Esto refuerza que muchas consultas pequeñas tienen coste también en Node y transporte, aunque PostgreSQL las ejecute rápidamente. No se propone parchear esa dependencia.

## 4. Mejora candidata y contrato que debe conservar

### Primera intervención recomendada: atajo positivo para una concesión ya realizada

**Objetivo:** una lectura repetida del mismo periodo no vuelve a abrir el recorrido de concesión si ya hay una concesión gratuita registrada. Mantener frescos saldo, estado de cuenta y configuración; **no cachear crédito ni elegibilidad**.

Cambios propuestos, todavía no implementados:

1. En `runDripForAccount`, seguir leyendo configuración de forma normal. Si la función está desactivada o la cantidad es 0, retornar 0 antes de abrir una transacción inútil.
2. Calcular la misma clave UTC de periodo actual, sin cambiar el significado de `now` inyectable.
3. Para concesiones gratuitas, comprobar positivamente si ya hay fila de ese account/periodo con subscription_id nulo. Formular la búsqueda para aprovechar el índice único existente de `COALESCE(subscription_id::text,'free'), account_id, period_key`; verificar el plan y no crear un índice duplicado.
4. Si existe, retornar 0: el handler seguirá leyendo el balance real y construyendo su respuesta normalmente. No hay nuevos minutos, saldo cacheado ni autorización de gasto.
5. Si no existe, entrar en la transacción actual conservando todos los guards y **el INSERT ON CONFLICT DO NOTHING**. La comprobación externa no sustituye la idempotencia: dos peticiones pueden ver ausencia simultáneamente.
6. No ampliar este atajo automáticamente a concesiones de suscripción pagada, presupuestos globales, reservas o liquidaciones.

**Tradeoff:** el primer intento de concesión tiene una lectura adicional; las lecturas repetidas evitarían varios roundtrips y transacciones que hoy terminan sin efecto. Con el hot set de este ensayo, la enorme mayoría son repetidas; debe medirse también el escenario de muchas cuentas nuevas para no trasladar el coste sin verlo.

La tabla de grants no tiene en la migración revisada un trigger de inmutabilidad equivalente al ledger. Por tanto, verificar su ciclo de vida, retención y conciliación antes de convertir el atajo en garantía general; no declarar que una fila nunca puede desaparecer. La optimización solo evita trabajo tras una presencia observada, no autoriza nuevas escrituras ni reabre concesiones.

### Alternativas que no se recomiendan como primer paso

- **Cachear el catálogo/configuración:** podría ahorrar lecturas adicionales, pero exige definir invalidación de cambios del operador. No es necesario para eliminar la transacción repetida de este caso.
- **Mover concesiones a un worker:** cambia cuándo están disponibles los minutos y cómo se recuperan fallos. Es una decisión de comportamiento mayor que un atajo equivalente.
- **Aumentar pool o añadir índices por intuición:** no elimina las operaciones repetidas. Los tiempos medios SQL medidos no prueban necesidad de un índice nuevo.
- **Desactivar OTel/logs:** pierde observabilidad sin demostrar que resuelva el límite. Perfilar costes concretos no autoriza retirar auditabilidad.

## 5. Pruebas exigidas antes de aceptar la mejora

Implementar con SDD/Superpowers y RED→GREEN sobre PostgreSQL aislado. El requisito no es solo devolver 0: también reducir trabajo y mantener persistencia.

- Configuración desactivada o cantidad 0: devolver 0, no abrir transacción de concesión, no emitir grants/entries ni modificar saldo.
- Concesión gratuita existente del mismo periodo: devolver 0 y conservar balance/ledger/grants; demostrar que se evita el recorrido de INSERT/BEGIN/COMMIT usando observación real de consultas, no resultados simulados.
- Cuenta nueva elegible: conservar exactamente la concesión y rollover anteriores; documentar la consulta adicional.
- Dos primeros intentos concurrentes: una sola concesión y una sola entrada financiera; después de la carrera, las siguientes lecturas usan el atajo.
- Cambio de mes UTC: el registro anterior no suprime el grant del nuevo periodo.
- Guest, suscripción activa, saldo reservado, wallet al límite y presupuesto global: conservar condiciones previas.
- Activación/desactivación o cambios de política entre peticiones: sin caché que mantenga configuración antigua.
- Fallo de lectura de configuración/consulta, cancelación de HTTP y cambios de cuenta: no transformar un error en concesión nueva ni devolver saldo de otra cuenta.
- Verificar todas las invocaciones de `runDripForAccount`, no solo `/v1/minutes`; preservar firma y resultados históricos.

Después: suite API completa, revisión independiente por tratarse de crédito/concurrencia, y comparación antes/después con mismo SHA base, datos, mezcla, recursos y generador. Repetir varias veces; publicar consulta/transacción evitada además de latencias, no solo RPS.

## 6. Evidencia y estado

Perfiles y consultas en `openspec/changes/api-performance-hardening/evidence/profile/` (ignorado): cpu profiles de ambas variantes, `profile.log`, `summary.json` y los harnesses/override usados. Se validó sintaxis de los scripts y se cruzaron llamadas/filas/configuración e invariantes con el log real.

Ambos stacks terminaron con 200 cuentas, saldo monetario igual al journal 199999999991 nanoUSD, 1 usage y 1 intento settled de preflight. La lectura de minutos también concedió grants gratuitos reales en el esquema sintético; esa actividad forma parte del comportamiento medido, no dinero/proveedor real. Los contenedores, volúmenes y redes del experimento se limpiaron.

No se modificó código de producto, no se ejecutó la optimización propuesta ni se reejecutó la suite funcional completa por esta tarea diagnóstica. No hay evidencia antes/después de un fix todavía. Sigue sin haber certificación de producción, voz, TLS o historial grande.

**Siguiente paso concreto:** aprobar y ejecutar el atajo de concesión ya existente con las regresiones anteriores; solo después volver a evaluar si hace falta aislamiento de procesos o escalado HTTP. No se aumenta ninguna cuota en este diagnóstico.
