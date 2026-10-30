# Capacidad acotada del proxy y prueba local

Esta fase conserva el proxy no-streaming, la cuota por cuenta y la liquidación basada en usage conocido. No certifica despliegue multiinstancia ni capacidad de producción.

## Configuración

| Variable | Default | Rango |
|---|---:|---:|
| PROXY_MAX_CONCURRENT | 2 | entero positivo seguro |
| PROXY_MAX_TOKENS | 4000 | entero positivo seguro |
| PROXY_MAX_IN_FLIGHT | 10 | 1–1000 |
| PROXY_TIMEOUT_MS | 30000 | 1–300000 ms |
| PROXY_MAX_RESPONSE_BYTES | 1048576 | 1024–16777216 bytes |

Valores vacíos/fraccionarios/no finitos se rechazan. El cupo global es de esta instancia del proxy, no de una flota ni de otras integraciones con el mismo proveedor. No hay cola ni reintentos automáticos.

El deadline empieza en admisión; se comprueba alrededor de SQL y cancela fetch/lectura del cuerpo. SQL conserva sus propios plazos (adquisición5s, statement10s): el deadline no promete cortar una consulta SQL ni una liquidación ya iniciada. El límite de bytes se aplica al cuerpo realmente recibido, también si es error. `requestTimeout` de Fastify no sustituye esta cancelación.

## Contrato HTTP

- Éxito200 conserva payload y metering anteriores.
- account_busy429, token_balance_exhausted402 y streaming_unsupported_v1 400 se conservan.
- Nuevo proxy_busy503 con `Retry-After: 1`: cupo de instancia agotado; no se llamó al proveedor.
- Nuevo provider_timeout504: venció el presupuesto de esta operación; NO demuestra que el proveedor no haya consumido.
- provider_error502: fallo de transporte, HTTP, JSON, usage o tamaño. No se revelan errores/cuerpos del proveedor.

Una desconexión no es una invitación a reintentar automáticamente una operación facturable. Solo se responde cuando la liquidación de un éxito queda confirmada. Si el cliente desaparece después de obtener usage, esa liquidación continúa.

## Intentos y conciliación

La nueva migración055 añade `proxy_attempts` y eventos inmutables de transición, y un vínculo único nullable en proxy_usage para preservar datos históricos. Estados prepared→dispatched→settled/rejected/unknown. La transición se valida en BD; estados terminales no se reabren. Settlement, usage y estado final se escriben en la misma transacción y referencia estable `proxy:<attempt UUID>`, evitando doble débito por repetición interna.

Crash antes/después del envío puede dejar dispatched incluso si no llegó a ejecutarse: se interpreta conservadoramente. El intento no contiene prompts, cuerpos, claves ni respuesta. Unknown no es una reserva ni garantiza recuperar costes: no se inventan débitos o usage, ni se retransmite automáticamente. Se mantiene la política histórica de grace; esta fase no rediseña financiación concurrente.

Consulta operativa (solo consola autorizada; no enviar resultados identificables a telemetría):

```sql
SELECT id, account_id, rate_version, state, created_at, updated_at
FROM proxy_attempts
WHERE state = 'unknown'
   OR (state IN ('prepared','dispatched') AND updated_at < now() - interval '10 minutes')
ORDER BY updated_at, id;
```

La antigüedad no prueba muerte de otro proceso. Revisar evidencia del proveedor y ledger antes de cualquier corrección; aplicar el proceso financiero de ajustes autorizado, nunca editar ledger, reabrir estados o cobrar por estimación. Si no existe evidencia de usage, conservar incertidumbre para seguimiento. Un fallo al actualizar después de enviar deja el intento dispatched durable y un diagnóstico seguro.

## Prueba aislada

`make load-api-smoke` construye y ejecuta PostgreSQL, migración, seed, API y stub en proyecto único y red interna, sin puertos publicados ni env_file. El runner no hereda secretos de la shell. `make load-api-baseline` amplía duración; los resultados del host no equivalen a cuotas reales del proveedor. Ver `../load/README.md`.

Antes de aumentar recursos, observar colas/latencia/memoria y conservar invariantes financieras. Voz sigue teniendo líder único; separar workers, distribuir límites y cambiar locks se decide tras medir.
