# Diseño: capacidad API, primera fase

## Intención y autorización
El usuario solicita «quiero que crees una rama nueva y siguiendo sdd de superpowers implementes los cambios necesarios para informe-performance-api-2026-10-30.md» y aprueba empezar por generador, observabilidad y límites del proxy, medir aislado y decidir después las mejoras condicionales. Este diseño concreta esa primera fase, no convierte todas las recomendaciones del informe en requisitos inmediatos.

Estado: pendiente de aprobación del diseño. Rama `feat/api-performance-hardening`. Implementación y plan detallado vendrán después de aprobarlo.

## Enfoques considerados
1. **Incremental sobre Node/Fastify/pg existentes — elegido por el usuario.** Corregir medición y limitar recursos antes de escalar. Menos infraestructura y evidencia comparable; no resuelve todavía el singleton de voz.
2. Implementar ya toda la separación y distribución. Permite capacidad adicional potencial, pero añade propiedad distribuida, migraciones y riesgos financieros antes de saber qué limita. Diferido.
3. Sustituir el driver por k6 desde el principio. Buen motor de llegadas, pero no evita especificar fixtures y corregir assertions. Para esta fase se mantiene Node sin dependencia nueva; k6 queda opcional.

Los valores y detalles siguientes son propuestas técnicas del agente, no preferencias previamente expresadas por el usuario.

## 1. Driver de carga fiable y aislado

Separar funciones importables de planificación/transporte/reporte del entrypoint `services/api/load/loadtest.mjs`. Importar utilidades no ejecuta tráfico. Mantener las fases existentes como preset, permitiendo duraciones/tasas acotadas para smoke. Inyectar reloj/transporte en pruebas, sin depender de tiempos reales para assertions del planificador.

### Corrección y métricas
- Representar tareas con método/path explícitos: sin tokens deben solicitarse `/v1/pricing`, `/v1/minutes/products` y `/healthz`, nunca `undefined`.
- Índice de lanzamiento monotónico independiente de completadas. Rotación de cuentas y de exactamente NETWORKS redes sintéticas, validado como entero positivo.
- Planificación de llegadas con máximo de trabajo en vuelo y deadline por petición. Si no hay cupo o el generador va retrasado, contabilizar llegadas no lanzadas; no intentar recuperar una cola ilimitada.
- Reportar ofrecidas, lanzadas, completadas durante ventana, completadas al drenar, útiles, rechazadas por status y fallos de transporte. Distinguir tiempo de oferta y total con drenaje, tasas ofrecida y de completado útil, percentiles de todas las respuestas y de éxitos por separado. Dataset vacío produce percentiles nulos, no latencia cero ficticia.
- Validar shape mínimo de respuesta y precondiciones. Los rechazos de saldo/auth/capacidad no son operaciones útiles; el script termina no-cero ante fallo de preflight o criterio configurado incumplido, después de emitir resumen.

### Seguridad de ejecución
El driver requiere modo explícito de entorno aislado, BASE explícita y allowlist exacta de hostname local (`localhost`, loopback o nombres de servicios del Compose de carga). Rechazar otros destinos y redirecciones, no permitir bypass mediante un flag genérico. Un nombre permitido es una barrera contra errores, no prueba de aislamiento: usar Compose dedicado y preflight de fixtures. La fase proxy solo puede operar con proveedor stub configurado en ese Compose, nunca con el stack persistente.

Crear stack de carga dedicado, redes internas y volúmenes sintéticos propios; proceso de seed crea cuentas/saldos/tokens efímeros de prueba. No usar `services/api/load/tokens.txt`, `.env` ni credenciales existentes. Fixtures y stubs no tienen salida a Internet; no imprimir tokens, headers ni cuerpos. Arranque/migraciones deben funcionar sin depender de Google, Stripe o proveedores externos. Limpieza elimina únicamente recursos del proyecto de carga creado por esta ejecución.

Defaults propuestos del driver: máximo 100 en vuelo, timeout 5 s HTTP y 35 s fase proxy; parámetros enteros positivos con máximos explícitos documentados. Las tasas/duraciones de baseline no son garantías de capacidad.

## 2. Señales mínimas de saturación

Extender `metrics.ts` y `observability.ts` con:
- event-loop delay (segundos), utilización del event loop, RSS y heap (bytes);
- gauge de operaciones proxy en vuelo y contadores de rechazo por causa cerrada (`account`, `global`);
- duración del proveedor y resultado cerrado (`ok`, `http_error`, `transport_error`, `timeout`, `oversize`, `invalid_usage`, `client_cancelled`);
- contador de intentos cuyo resultado externo requiere revisión.

Preservar métricas actuales, no sustituirlas. Utilizar APIs nativas Node y OTel ya instaladas; activar monitores solo cuando OTEL esté habilitado, desactivarlos y retirar callbacks/timers al shutdown, con arranques repetidos testeados. No añadir labels por cuenta, URL, request id, token o texto. Instancia por defecto única por proceso mediante UUID generado al arrancar; respetar `OTEL_SERVICE_INSTANCE_ID` explícito. Verificar propagación hasta exportador y no confundir el UUID con identidad de sesión Engram.

Para esta fase los gauges existentes de pool permiten detectar cola; medir adquisición de todas las variantes de `pg.Pool` se difiere para evitar instrumentación incompleta o wrappers que rompan overloads. Documentar esta limitación. Incorporar dashboard/señales documentadas; las alertas y nombres exactos se fijan en el plan conservando compatibilidad con la traducción OTel→Prometheus. Sin declarar SLO comercial nuevo.

## 3. Proxy con presupuesto explícito

### Configuración
Mantener `PROXY_MAX_CONCURRENT` por cuenta (default 2) y el contrato no-streaming. Añadir propuestas:

| Variable | Default | Rango permitido |
|---|---:|---:|
| PROXY_MAX_IN_FLIGHT | 10 | 1–1000 |
| PROXY_TIMEOUT_MS | 30000 | 1–300000 |
| PROXY_MAX_RESPONSE_BYTES | 1048576 | 1024–16777216 |

Validar también PROXY_MAX_TOKENS como entero positivo seguro; no cambiar su default 4000. Nuevas opciones omitidas en constructores/tests existentes reciben defaults, para no romper consumidores internos. Documentar y pasar configuración en Compose; no interpretar cadena vacía como cero válido.

El límite global es **del servicio proxy en un proceso**, no de toda una flota ni del proveedor a través de otros servicios. Actualmente se configura un solo proveedor para este proxy: no añadir un mapa de proveedores innecesario. El despliegue multiinstancia seguirá sin estar certificado.

### Admisión y deadlines
Comprobar streaming y cuota de cuenta como hoy. Adquirir cupos de cuenta y global síncronamente, sin await entre check e incremento; no hay cola. Rechazo global: HTTP 503, código nuevo `proxy_busy`, `Retry-After: 1`; rechazo por cuenta conserva `account_busy`/429. Reflejar código y cabecera en contrato/docs/tests.

Iniciar deadline al admitir y aplicarlo a preparación y espera del proveedor; nunca continuar hacia fetch si ya venció durante SQL previo. No intentar cancelar a ciegas una transacción de liquidación en curso. El límite de tiempo no garantiza cortar SQL más rápido que los plazos existentes del pool/statement: comprobar señal antes y después de esa preparación y documentar el límite. El timer se limpia en todas las salidas.

Transmitir señal de cancelación al fetch y a lectura de cuerpo. Limitar bytes realmente leídos (incluida respuesta de error), cancelar reader al superar máximo; no fiarse solo de Content-Length. Parsear JSON solo dentro del límite. No introducir reintentos automáticos. HTTP >=400, JSON/usage inválido y cuerpo excesivo conservan `provider_error`/502; deadline explícito añade `provider_timeout`/504. Un error de transporte se normaliza a `provider_error`/502 sin exponer detalles del proveedor.

El cupo se libera en finally al terminar transporte/lectura y las escrituras asociadas; nunca una mera carrera de promesas que deje fetch colgado por detrás. La integración de desconexión usa cierre real de la respuesta/aborto, no `request.close` indiscriminado al terminar de leer el request. Eliminar listeners para evitar fugas.

### Contabilidad y resultados inciertos
La cancelación del cliente no es prueba de que no hubo consumo. Añadir migración nueva de tabla auditada de intentos proxy; no modificar migraciones existentes ni reescribir ledger. Cada intento admitido con saldo y tarifa válidos persiste UUID interno, cuenta, tarifa, estado y timestamps antes de enviar. Sin body, credenciales ni respuesta. Estados: `prepared`, `dispatched`, `settled`, `rejected`, `unknown`.

Persistir `dispatched` antes de invocar proveedor. Crash en esa frontera es conservadoramente incierto, aunque pudiera no haberse enviado. Solo una respuesta válida con usage y liquidación SQL confirmada permite `settled`. Escribir liquidación, proxy_usage y estado final en la misma transacción, con referencia estable por intento y unicidad para impedir doble débito al repetir esa finalización interna. Preservar grace/saldo mínimo y payload de éxito actuales; no añadir reservas ni rediseñar política de saldo en esta fase.

Si ya se conoce usage válido al desconectarse cliente, terminar liquidación una vez y no enviar respuesta. Si abortar pierde usage, registrar `unknown`; no inventar débito, no reintentar proveedor ni devolver éxito. Error explícito sin usage se registra como `rejected` solo cuando su clase demuestra que no hubo completion utilizable; respuestas incompletas/ambiguas se registran conservadoramente como `unknown`.

Intentos `prepared`/`dispatched` de un proceso que desaparece deben verse como pendientes de revisión mediante consulta documentada por antigüedad; no convertir automáticamente los de otro proceso en fallidos al arrancar. Documentar consulta y procedimiento manual de conciliación con evidencia externa, sin automatizar cobros ni reenvíos. Esta trazabilidad **no garantiza recuperar usage que el proveedor no expone** ni corrige otros riesgos preexistentes de financiación concurrente. Si falla persistir estado después de enviar, el `dispatched` durable sigue indicando incertidumbre; registrar solo diagnóstico seguro.

En shutdown dejar terminar trabajo conocido dentro del presupuesto y abortar transporte pendiente manteniendo intento durable; cerrar DB después de finalizar estas escrituras. No aumentar cuotas ni eliminar protecciones financieras existentes.

## 4. Baseline local y criterios

Preparar smoke reproducible del stack aislado; después una baseline de duración acotada configurada explícitamente, con proveedor falso rápido/lento/colgado y rutas autenticadas y públicas. Guardar SHA, parámetros sin secretos, hardware/cuotas, datos, tasas útiles, p95/p99, rechazos, memoria y cola del pool. No imponer los ejemplos de SLO del informe como contrato aprobado.

La baseline debe mostrar el efecto de admisión/deadlines y recuperación después del pico; no extrapolar proveedor falso a cuota/latencia real. Resultados del host son señal local. Si Docker, runner o métricas no están disponibles, registrar prueba pendiente y no declarar capacidad verificada.

Los informes de pruebas van al directorio ignorado de evidencias del change; `verify-report.md` resume resultados sin tokens. Sin carga contra producción ni proveedores reales.

## 5. Verificación y entrega

Pruebas-first deterministas con node:test/tsx existentes, reloj y transporte controlados. Integración PostgreSQL real efímero por `make test-api`; las pruebas de config y driver no requieren BD y no deben quedar saltadas por falta de TEST_DATABASE_URL. Type-check temprano con Node compatible (>=22.19). Ejecutar regresiones de contratos/rutas y suite API completa; sin cambios UI no se exige E2E del panel, pero cualquier comprobación no ejecutada se informa.

Cubrir todos los defectos de M1; límite global entre cuentas; cuota previa por cuenta; éxito y liquidación histórica; saldo insuficiente; deadline antes de fetch y durante headers/body; respuesta excesiva o tardía; disconnect antes/después de usage; error SQL, abort y shutdown; estado incierto tras crash y finalización repetida; redacción de logs/labels; ciclo de vida OTEL. Las pruebas financieras verifican respuesta, saldo, ledger, proxy_usage y estado durable, no solo status.

Riesgo alto: asincronía, contrato HTTP y contabilidad. Tras checks propios, verificación independiente de toda la fase y regresiones de hallazgos. Commits convencionales acotados, en rama activa, sin atribución IA, sin push/PR. Mantener tasks y verify-report con evidencia real. No usar workflows Gentle AI.

## No objetivos y decisiones diferidas
Sin Redis/k6/dependencias nuevas, cambios en voz o helpers, locks financieros, índices de negocio, PgBouncer, cachés, separación de procesos ni infraestructura remota. No certificación de producción ni garantía exactly-once del proveedor. Las siguientes fases solo se proponen si las mediciones las justifican.
