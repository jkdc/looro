# Auditoría de implementación de suscripción LLM y provider API

Fecha: 2026-11-01. Rama: `feat/proxy-api-provider`.
Revisión: `6625bd9..14aea6cb4685fd38c3a7d186743b3976480d1c66`.

## 1. Dictamen

**No está perfectamente implementado ni hay evidencia suficiente para afirmar ausencia de errores. No recomiendo habilitar dinero real ni publicar el release como implementación completa de esta arquitectura.** Hay errores concretos en acreditación de cuotas, aislamiento de reservas, operaciones administrativas y cableado Android. iOS sigue sin integrar el nuevo motor.

La base es aprovechable: admisión con reserva bajo lock, estados persistidos de intentos, construcción canónica para rutas con propósito, credenciales del proveedor en servidor y separación entre política informativa y autorización. Sin embargo, la existencia de esas piezas y una suite verde no prueban el flujo de suscripción completo.

Este informe propone correcciones; **no las implementa**. No se han cambiado fuentes, creado commits, usado proveedores reales ni accedido a una BD de producción.

### Fuentes y alcance

- Informe de partida: `informe-arquitectura-suscripcion-llm-2026-11-01.md`.
- Resultado relevante: `openspec/changes/proxy-api-provider/verify-report.md` (no los otros documentos homónimos del repositorio).
- Contraste con `proposal.md`, `design.md`, `tasks.md` y `specs/proxy-routing/spec.md` del mismo change, y con el código y los tests.
- Implementación principal: `d16ce32` (backend), `7e2447b` (móvil), `14aea6c` (documentación). El checkpoint `6625bd9` es la base de comparación; no se atribuye a este change todo el histórico anterior.
- Revisión independiente de backend y móvil, con contraste del coordinador sobre los puntos principales. No es certificación de seguridad ni ejecución exhaustiva de todas las combinaciones de entorno.

## 2. Resumen de hallazgos

Alta: corregir antes del piloto con consumo facturable. Media: corregir antes de declarar el flujo completo y fiable.

| ID | Prioridad | Hallazgo |
|---|---|---|
| B1 | Alta | Desactivar cuota gratuita impide también acreditar suscriptores |
| B2 | Alta | Scheduler sin recuperación intradía ni incorporación de altas posteriores |
| B3 | Alta | Liquidación puede gastar fondos reservados por otra llamada |
| B4 | Alta | `grant-quota` persiste cambios aunque rechace la cuenta o falle auditoría |
| B5 | Alta | Conciliación libera intentos activos y confirma antes de auditar |
| A1 | Alta | Release Android predeterminado sigue usando transporte/gates realtime antiguos |
| A2 | Alta | BYOK se oculta visualmente, pero sigue siendo ejecutable en release |
| A3 | Media | Fallo inicial de política deja bloqueado el motor sin recuperación normal |
| A4 | Media | Respuestas tardías de política contaminan el estado de otra cuenta |
| B6 | Media | `set-route` no actualiza el modelo publicado como etiqueta |
| D1 | Decisión contractual | Crédito acumulable y política de “cuota diaria” no significan lo mismo |

## 3. Backend: errores y corrección propuesta

### B1. Los suscriptores no reciben grants con la configuración predeterminada

**Evidencia:** `services/api/src/proxy-quota.ts:37-44`. El retorno anticipado exige allowance gratuito positivo antes de consultar las suscripciones. Los dos valores gratuitos tienen default cero.

**Reproducción observada:** invocar la función real `runDailyQuotaGrants` con `{dailyInputTokens:0,dailyOutputTokens:0}` y un adaptador que registra accesos SQL devuelve `{granted:0}`, con **cero consultas**. Por tanto, tampoco puede descubrir suscripciones activas.

**Cómo corregir:** procesar siempre las suscripciones; aplicar el condicional de cuota gratuita exclusivamente al barrido de miembros sin suscripción. No exigir activar el funnel gratuito para financiar un plan de pago.

**Prueba que debe fallar antes del arreglo:** PG aislado, suscripción activa y allowance gratuito cero; verificar entrada de ledger, saldo y segundo intento idempotente sin duplicación.

### B2. Se pierde un día de cuota por fallos transitorios o altas posteriores

**Evidencia:** `services/api/src/main.ts:203-215`. Se guarda `lastQuotaDay` antes de completar la promesa. Un fallo parcial o total impide reintentar ese día. `tokens-admin.ts:166-181` asigna el plan, pero no acredita el primer período: un alta posterior al barrido tampoco entra hasta el próximo día/reinicio.

**Cómo corregir:** barrer periódicamente grants pendientes, con un único vuelo local y reintentos acotados; usar la idempotencia persistida por cuenta/fecha, no una marca global diaria. El alta debe quedar cubierta por un barrido posterior o por acreditación inicial transaccional. Esperar el vuelo del job al apagar antes de cerrar la BD.

**Regresiones:** fallo y recuperación el mismo día, alta tras el primer barrido, fallo después de acreditar solo parte de las cuentas, dos réplicas y apagado con job activo. Cada cuenta recibe exactamente un grant efectivo.

### B3. Una liquidación invade reservas ajenas

**Evidencia:** `services/api/src/proxy-attempts.ts:65-75`. Se limita el débito al saldo completo, no al saldo descontando otras reservas. `ledger.ts:69-73` comprueba que la reserva no sea negativa, pero no impide saldo disponible negativo.

**Escenario:** saldo 100; A reserva 40 y B reserva 60. A devuelve coste real 70; liquida 70 y libera 40. Quedan saldo 30 y reserva 60: disponible **−30** para una llamada B ya enviada.

**Reproducción:** el revisor ejecutó la función real `settleProxyAttempt` con adaptador SQL en memoria y obtuvo liquidación 70, saldo 30 y reservado 60. Es prueba del flujo/aritmética, **no** prueba de locks en PostgreSQL. El estimador `JSON.stringify(body).length / 4 + 256` (`token-proxy.ts:50-53`) no garantiza un límite superior de tokens de entrada; el cap de salida no elimina ese riesgo.

**Cómo corregir:** bajo los locks financieros, calcular fondos liquidables sin consumir reservas ajenas: `balance − (totalReserved − ownHold)`. Definir expresamente qué hacer con un exceso: registrar coste confirmado, diferencia pendiente/subsidiada y estado de conciliación; no presentar un cobro parcial como liquidación íntegra. Usar estimación realmente conservadora y validación del contenido admitido. No basta con añadir un CHECK y provocar rollback después del gasto externo.

**Regresión:** dos reservas y sobrecoste realista de A, liquidaciones en ambos órdenes y reintento. Verificar saldo, reservas, usage, diferencia no cobrada e idempotencia; B conserva su respaldo.

### B4. Alta de cuota y auditoría no son atómicas

**Evidencia:** `services/api/src/tokens-admin.ts:174-180`. `setSubscription` confirma el upsert antes de la transacción de validación de miembro/auditoría.

**Escenario:** un guest existente recibe el upsert; después `memberAccount` rechaza el comando. Queda una suscripción persistida sin auditoría pese al error. Un fallo al escribir auditoría también deja el cambio confirmado. El barrido de suscriptores (`proxy-quota.ts:43-44`) no filtra defensivamente invitados/cuentas eliminadas.

**Cómo corregir:** validación, locks, upsert y auditoría con un único cliente SQL en una sola transacción. Filtrar elegibilidad también en el job. Mantener orden de locks de cuenta y wallet.

**Regresiones:** guest, cuenta eliminada y fallo inyectado de auditoría; la operación rechazada no cambia plan, ledger, saldo ni auditoría.

### B5. La conciliación libera reservas activas y puede perder su auditoría

**Evidencia:** `services/api/src/proxy-attempts.ts:86-95` solo exige reserva abierta, sin comprobar `proxy_attempts.state`; `tokens-admin.ts:186-192` audita después de confirmar la liberación.

**Impacto:** una operación descrita como conciliación de `unknown` admite `prepared` o `dispatched`, devolviendo fondos de una petición en curso. Si falla la auditoría, el operador recibe error aunque los fondos ya estén liberados.

**Cómo corregir:** bloquear cuenta/wallet/intento/reserva en orden consistente; exigir estado conciliable `unknown`; liberar y auditar en la misma transacción. Registrar evidencia y resultado de conciliación. Para usage confirmado, añadir un camino explícito de liquidación auditada: la implementación actual solo permite liberar, aunque la especificación contemple liberar o liquidar.

**Regresiones:** rechazo sin efectos de intentos activos, liberación de `unknown`, repetición, carrera con liquidación y rollback por fallo de auditoría.

### B6. El endpoint no refleja el cambio de modelo del operador

**Evidencia:** `tokens-admin.ts:148-152` cambia `model`/`output_cap`; `proxy-policy.ts:27-32` publica `display_name`, que conserva el seed.

**Cómo corregir:** diferenciar identificador real y etiqueta de presentación, actualizar ambos mediante el comando o publicar ambos explícitamente. El nombre de presentación no debe utilizarse como identificador del proveedor en el cliente.

**Regresión:** modificar ruta mediante `set-route`, consultar `/v1/conversation-policy` y ejecutar una llamada; comprobar coherencia entre identificador, etiqueta y modelo realmente reenviado.

## 4. Android e iOS

### A1. El release predeterminado no usa el nuevo camino de voz

**Evidencia:** `AppViewModel.kt:631-640` cambia el default efectivo de `openai` a `api` en release; `692-693`, `2611-2629` y `2668-2692` siguen decidiendo transporte, gates e inicio mediante el valor original `BuildConfig.CONVERSATION_ENGINE`. Gradle conserva `openai` por defecto (`apps/android/app/build.gradle.kts:25-32`).

**Escenario:** release limpio, bearer/crédito válidos, sin clave personal ni minutos: la conversación sigue bloqueada por el camino realtime previo. Con clave personal puede seguir ese camino en vez del proxy.

**Cómo corregir:** una única resolución efectiva de motor, transporte y capacidades, aplicada a inicio, voz, helpers, disponibilidad, preferencias y eventos. `api` debe conectar el transporte local al adaptador de proxy. No sustituir ciegamente todas las comparaciones sin verificar qué capacidad determina cada una.

**Regresión:** release con build-default `openai` inicia voz vía proxy con `typed_reply`, sin crear sesión realtime ni exigir minutos/clave personal; debug conserva el flujo anterior.

### A2. Ocultar BYOK no impide su ejecución

**Evidencia:** `AppViewModel.kt:632,639-640` conserva motores distintos de `openai` y selección persistida; el filtrado release está en la lista visual (`1836-1843`), no en el switcher. `selectLocalEngine` (`1849-1851`) tampoco aplica la restricción; `LocalEngineSwitcher.kt:15-17,44-55` instancia IDs del registro.

**Cómo corregir:** allowlist por build type aplicada tanto a resolución inicial/persistida como al setter y construcción del adaptador. Normalizar BYOK antiguo a `api` en release. Conservar los proxy-backed expresamente autorizados y el catálogo debug. Este hallazgo no demuestra claves embebidas: demuestra incumplimiento de la política de ejecución.

**Regresiones:** cada valor BYOK en flags y preferencias de release se normaliza; debug lo conserva; comprobar artefacto release real, no solo tests debug.

### A3. El cache informativo se convierte en dependencia bloqueante

**Evidencia:** `ConversationPolicyStore.kt:21-29` arranca vacío en memoria; `AppViewModel.kt:647-654` silencia errores de GET, refrescado desde cambio de cuenta (`1955-1957`); `OpenAICompatibleEngine.kt:168-169` falla antes de enviar si no hay modelo.

**Cómo corregir preferentemente:** desacoplar el despacho API del cache: el servidor ya elige modelo, por lo que la llamada con propósito no debe necesitar una etiqueta para funcionar. Si se mantiene esa dependencia, introducir estados explícitos y recuperación reintentable, cache por cuenta y refresco ante los eventos documentados. No tratar como cosmético un fallo que bloquea servicio.

**Regresión:** GET inicial falla, red se recupera y reintentar funciona sin logout/login; incluir llamada mientras el GET sigue pendiente.

### A4. Respuestas tardías mezclan cuentas

**Evidencia:** `AppViewModel.kt:647-654` publica sin comprobar identidad/revisión ni cancelar el job anterior; el store no dispone de owner/clear. Una respuesta lenta de A puede sobrescribir B después de cambiar cuenta o cerrar sesión.

**Cómo corregir:** limpiar al cambiar identidad, cancelar vuelo anterior y verificar cuenta/revisión al publicar. Propagar cancelación, no absorber `CancellationException`.

**Regresiones:** A→B con orden de respuestas invertido y A→logout con GET pendiente. Es contaminación de estado local, no bypass de autorización del servidor.

### iOS: falta implementación de App, no solo compilación

`apps/ios/Core/ApiProxyEngine.swift` existe, pero no está conectado al coordinador. `apps/ios/App/ConversationCoordinator.swift:477-479` sigue con `.personalKey`, `.hostedMinutes`, `.tokens`; `729-735` conecta el proxy anterior. Faltan selección API, política, gating release y pruebas de App.

Es un pendiente ya reconocido parcialmente por el verify-report, no un descubrimiento de fallo de compilación. **Pasar el gate macOS sobre el archivo aislado no completa el wiring pendiente.**

## 5. Decisiones y diferencias respecto al informe original

### D1. Crédito diario acumulable no equivale a cuota diaria estricta

Los grants son entradas `purchase` permanentes, sin expiración (`proxy-quota.ts:46-49,62-63`). Se acumulan entre días y sobreviven a la expiración del plan. En cambio, `conversation-policy.ts:18-28` calcula `exhausted` sumando tokens diarios de entrada y salida; admisión usa coste ponderado y saldo menos reservas.

Por eso el endpoint puede decir agotado teniendo crédito acumulado, o no agotado sin fondos suficientes para la siguiente reserva. El informe original pedía grants con expiración. No se debe decidir silenciosamente la política de rollover durante un arreglo técnico.

**Recomendación:** si el producto promete cuota diaria, modelar un presupuesto por período con expiración/rollover explícito, separado de packs comprados y aplicado atómicamente al reservar. Preservar ledger inmutable mediante entradas compensatorias, nunca reescribir historia. Si se prefieren créditos acumulables por simplicidad, documentarlo y publicar saldo disponible monetario; dejar de presentar un tope diario estricto de tokens. La alternativa reduce complejidad, pero cambia la promesa comercial.

**Aceptación:** probar cambio de día con reloj inyectado, remanente, expiración, reservas cruzando medianoche, packs adicionales y mezcla entrada/salida. La política informativa y la admisión deben expresar la misma semántica, sin convertir la primera en autoridad.

### Otros límites que deben quedar explícitos

- **F06–F07 no están cerrados globalmente:** sin propósito se conserva forwarding legacy (`token-proxy.ts:102-107`), sin allowlist ni modelo canónico. Es compatibilidad autorizada por el SDD, pero no permite afirmar que toda llamada está fijada por servidor. Mantenerla acotada durante transición y probar `PROXY_REQUIRE_PURPOSE` antes de darla por cerrada. No se afirma un bypass HTTP mediante whitespace: no se reprodujo por esa interfaz.
- **Split de modelos:** el seed sirve MiniMax-M2 para todos los propósitos. El verify-report lo declara correctamente; el split potente/gratis requiere configuración y evaluación de JSON estructurado, no está activado por existir la tabla.
- **Providers por propósito:** la implementación usa una sola URL/clave upstream y cambia modelo. Es una reducción explícita del diseño, no failover/multi-provider completo.
- **Streaming, atestación y compra recurrente token:** fases posteriores, no defectos por su ausencia. La suscripción de minutos histórica no acredita automáticamente esta nueva `token_subscriptions`.
- **Error público:** proposal menciona `quota_exhausted`, mientras spec/tareas/implementación adoptan `token_balance_exhausted`; unificar documentos, sin cambiar el contrato por inercia.
- **Flag:** design documenta `PROXY_REQUIRE_PURPOSE=1`, pero `app.ts` exige la cadena `'true'`. Corregir documentación o aceptar explícitamente ambas formas con test.
- El informe original calcula mal el coste mensual: con su propio supuesto de aproximadamente 0,001 USD/turno, 100–300 turnos diarios durante 30 días son aproximadamente **3–9 USD/mes**, no menos de 0,10 USD/mes. Es aritmética del documento, no validación de precios actuales. Rehacer márgenes antes de fijar cuota comercial.

## 6. Qué prueban realmente las verificaciones

### Ejecutado en esta auditoría

| Comprobación | Resultado observado y límite |
|---|---|
| `npx tsc --noEmit`, API | Exit 0 |
| `make check` | Exit 0; 150 tests Python, schema y checks de exporters/paridad. Emitió ResourceWarnings SQLite; no prueban el flujo nuevo |
| Ejecución directa con `node --import tsx` de `proxy-quota.test.ts`, `proxy-attempts.test.ts`, `token-proxy.test.ts` | 17 casos: **2 ejecutados pasan, 15 omitidos por falta de TEST_DATABASE_URL** |
| Probe función real de grants con allowance cero | Devuelve cero sin consultar suscripciones |
| Probe de settlement con SQL en memoria, revisor independiente | Disponible negativo bajo escenario descrito; falta confirmación PG |
| Tests Android focalizados, intento del revisor | No arrancan: lock de wrapper Gradle en HOME no escribible |
| PostgreSQL/Docker | Socket Docker denegado en este entorno; no se repitió suite integrada |
| iOS/release/dispositivo/proveedor real/carga | No ejecutados aquí |

El runner `node --import tsx --test ...` devolvió tres ficheros exitosos sin detalle interno; se repitieron directamente para descubrir el resultado real de cada caso. No contabilizar ese primer resumen como 17 tests integrados aprobados. El entorno también emitió mensajes de stream fd no permitido; no se ocultan como evidencia de funcionalidad.

### Lagunas concretas del verify-report y los tests

1. El verify-report declara suite API verde, pero remite a un “resultado final abajo” que no contiene conteo, comando fechado ni salida final. Es una declaración histórica, no reproducción independiente.
2. `proxy-quota.test.ts:68-87`, llamado “quota exhaustion surfaces through the balance admission of a proxied call”, **no llama al proxy ni comprueba 402**: solo saldo y política. No demuestra su título. Llama primero con allowance cero y después positivo, ocultando B1.
3. Los tests del adaptador Android no cubren el transporte y los gates reales de release, recuperación del GET ni respuestas fuera de orden.
4. Validar sintaxis de alertas no prueba disparo, labels, entrega de notificación ni existencia de la serie en ejecución.
5. La cobertura del endpoint es de función, no del flujo HTTP autenticado y sus errores. Añadir pruebas públicas con bearer válido, ausente, expirado y de otra cuenta.

No se han demostrado aquí errores nuevos en exporters ni TypeScript. Tampoco se puede convertir la falta de ejecución PG/macOS/release en aprobación.

## 7. Plan de corrección y cierre

1. **Contabilidad y administración:** B3, B4, B5. Primero regresiones RED con PG aislado, después corrección mínima y GREEN. Validar estado persistido y no solo respuesta.
2. **Entrega de cuota:** B1, B2; resolver D1 antes de ampliar el modelo de períodos. Probar restart, retry, altas y réplicas sin reloj real.
3. **Contrato y cliente:** B6, A1–A4; una configuración efectiva Android y cache realmente informativo o recuperable. Documentar flags y errores coherentemente.
4. **Paridad real:** completar wiring iOS en App y ejecutar macOS; compilar y ejercitar release Android además de debug.
5. **Cierre de evidencia:** suite API completa en stack efímero, pruebas públicas de cuota/402 y auditoría, Android unit/lint/release, iOS, contratos y alertas. Añadir el perfil de carga con propósitos pendiente y runbook de conciliación probado.

Actualizar el SDD y verify-report con resultado por requisito, comando, entorno, fecha y pruebas omitidas. Los tests de cada defecto deben fallar antes del arreglo por su causa real. Ninguna compra/proveedor real/despliegue se incluye implícitamente en estas correcciones: requieren autorización propia.

### Criterio de aceptación

- [ ] Ninguna liquidación consume el respaldo de otra reserva.
- [ ] Operaciones administrativas rechazadas dejan estado intacto; auditoría y efectos son atómicos.
- [ ] Suscriptores reciben cuota con free allowance apagado y recuperan fallos intradía.
- [ ] Semántica de período/rollover y política informativa coherentes con admisión.
- [ ] Voz release y helpers llegan al proxy, sin ejecutar BYOK no permitido.
- [ ] Fallos y cambios de cuenta no bloquean ni contaminan política local.
- [ ] App iOS conectada y verificada; pendientes de carga/release cerrados con evidencia.

**Conclusión:** implementación parcial con errores corregibles, no lista para declararse completa. La prioridad es proteger la contabilidad y completar el camino real de consumo antes de optimizar latencia o activar pagos.
