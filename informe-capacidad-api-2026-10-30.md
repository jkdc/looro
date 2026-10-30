# Capacidad de la API: rango medido y preparación para crecer

**Fecha:** 30 de octubre de 2026. **Código medido:** `a47e42b`. **Rama:** `feat/api-performance-hardening`.

## 1. Respuesta práctica

**Tenemos un punto de operación local comprobado de aproximadamente 200 peticiones/s durante cinco minutos para una mezcla de lecturas concreta.** Se completaron 59983 de 60000 ofrecidas (199.94 útiles/s); 17 llegadas no llegaron a emitirse desde el generador. Todas las peticiones emitidas recibieron 200 con contenido válido.

**400 peticiones/s durante 30 segundos cumplieron los criterios exploratorios**, con 398.52 completadas útiles/s y p95 de 10.83 ms. **800 ofrecidas/s no quedan aprobadas:** hubo 15.35% de llegadas no emitidas, utilización del event loop de la API próxima al 99% y cola del pool PostgreSQL.

Estas cifras **no son la capacidad de toda la API ni de producción**, no equivalen a usuarios simultáneos y **no incluyen voz hospedada**. La conclusión útil hoy es:

- Para lecturas similares, 200 ofrecidas/s es la referencia local sostenida comprobada, no un límite nuevo impuesto al servidor.
- Hay margen para picos de ese perfil hasta 400 ofrecidas/s durante 30 s, sin demostrar que 400 sea sostenible indefinidamente.
- No dimensionar una instancia suponiendo 800/s de ese perfil: el experimento no entregó toda la carga y sí mostró presión real del proceso y de BD.
- El proxy tiene otro techo: con 10 slots y proveedor simulado de 2 s, 3 llamadas/s completaron sin rechazos; 5 llamadas/s ya produjo 9% de rechazos por capacidad.

**No recomiendo añadir Redis, subir pools o multiplicar réplicas automáticamente.** Primero hay que fijar el entorno objetivo y repetir con datos y tráfico representativos. Las mejoras de las secciones 6–7 quedan preparadas por disparador, implementación y verificación.

## 2. Qué representa esta medición

| Aspecto | Condición comprobada |
|---|---|
| Host | Linux x 64; 12 CPUs visibles; 32.7 GB de memoria de host |
| Recursos asignados | Sin límites CPU/RAM explícitos en este Compose; no equivale a un VPS pequeño |
| API / BD | Una API Node y un PostgreSQL 17; pool 10; configuración de la fase anterior |
| Red | Docker interna; acceso directo a Fastify, sin Internet, TLS o Caddy en el recorrido medido |
| Datos |200 cuentas/sesiones sintéticas; tablas pequeñas y conjunto caliente, no historia de producción |
| Población de red |2000 redes sintéticas para no confundir rendimiento con cuota por red |
| Mezcla HTTP |70% GET `/v1/minutes` autenticado; 30% pricing, productos y health |
| Proveedor | Stub local rápido durante lecturas; después 2 s artificiales para proxy |
| Generador | Un proceso; máximo 100 en vuelo; no recupera llegadas perdidas en ráfaga |
| Aislamiento | Stack y volúmenes propios; limpieza observada; no se modificó runtime ni datos existentes |

Las lecturas de saldo ejecutan su camino real de servidor/BD; no son respuestas mock de Fastify. No se hicieron login con Google/Apple, compras reales ni llamadas externas. Dos mil redes sintéticas son una condición de laboratorio, no una estimación de distribución real de usuarios.

### Criterios elegidos para explorar, no SLO comercial aprobado

Para cada escalón de lecturas: al menos 99% de oferta convertida en respuestas útiles, al menos 98% lanzada, p95 de éxito≤300 ms y p99 total≤1000 ms. Un 200 sin shape mínimo válido no cuenta como éxito; los 429/503 tampoco cuentan como trabajo útil. Llegadas no lanzadas aparecen separadas de errores HTTP.

La escalera se detuvo en el primer escalón no aprobado. Se ensayó recuperación a 50/s, cinco ventanas de un minuto a 200/s, una red compartida y cuotas del proxy con lecturas concurrentes. No se ejecutó una suite de pruebas simultáneamente con la carga.

## 3. Resultados HTTP

### Escalera de 30 segundos

| Ofrecidas/s | Útiles/ofrecidas | No emitidas | p95 éxito ms | p99 total ms | Criterio exploratorio |
|---:|---:|---:|---:|---:|---|
|50 |1500/1500 |0 |21.69 |41.61 |Cumple |
|100 |2999/3000 |1 |16.02 |31.06 |Cumple |
|200 |5989/6000 |11 |17.69 |35.34 |Cumple |
|400 |11957/12000 |43 |10.83 |56.13 |Cumple |
|800 |20315/24000 |3685 |227.84 |252.74 |No cumple por oferta no servida |

Todos los requests emitidos de esta escalera recibieron 200. Las latencias no crecen de forma monótona entre los escalones bajos: warmup, cachés y variación local influyen; no se atribuye esa variación a una optimización.

A 800 ofrecidas/s se completaron 675.53 útiles/s incluyendo drenaje, **no 800**. El generador también pierde llegadas y utiliza aproximadamente 50% de su event loop; no permite ubicar un techo exacto del backend. Sin embargo, la señal del servidor es independiente: su event-loop utilization alcanzó 0.988, el pool usó sus 10 conexiones y se observaron hasta 97 solicitudes de conexión esperando. Por tanto, tampoco sería correcto atribuir todo el incumplimiento al generador.

No se tomó perfil CPU ni planes SQL durante ese escalón: se ha localizado presión del proceso/BD, no demostrado qué función individual consume el tiempo. No se afirma que aumentar el pool resuelva el problema.

### Cinco minutos a 200 ofrecidas/s

| Minuto | Útiles/ofrecidas | No emitidas | p95 éxito ms | p99 total ms |
|---:|---:|---:|---:|---:|
|1 |11998/12000 |2 |9.16 |22.10 |
|2 |11991/12000 |9 |8.98 |18.10 |
|3 |11997/12000 |3 |9.34 |23.00 |
|4 |11998/12000 |2 |9.94 |22.52 |
|5 |11999/12000 |1 |7.34 |16.93 |

Total: 59983/60000 útiles, 99.972% de oferta, sin errores HTTP entre las emitidas. No hay evidencia de degradación progresiva en esas cinco ventanas; **cinco minutos no prueban ausencia de fugas ni estabilidad durante horas**.

La última muestra del minuto 1 mostró RSS 136.1 MB y event-loop utilization 0.331; al final del minuto 5,RSS 136.6 MB y utilización 0.294. El pico RSS observado en el experimento completo fue 138.3 MB (~132 MiB). Son muestras, no un límite de memoria garantizado. Los máximos de cola del escalón 800 no se arrastran como si fueran lecturas actuales de los minutos posteriores.

### Recuperación

Después del escalón 800, 50/s durante 15 s completaron 750/750 con p95 de 15.21 ms y cola 0 en la última muestra. Tras los escenarios de proveedor, la recuperación a 50/s completó 750/750 con p95 de 9.80 ms, proxy en vuelo 0 y cola 0. No fue necesario reiniciar la API.

## 4. Los límites de protección no son la capacidad de la máquina

### Redes compartidas

125 lecturas consecutivas desde una red sintética nueva produjeron **120 respuestas 200 y 5 respuestas 429**, con latencia baja. Se alcanzó el guard existente de 120 peticiones por ventana de 60 s (`services/api/src/app.ts:191–198`), no un fallo de CPU o de PostgreSQL.

Una oficina, universidad o red móvil compartida puede verse limitada antes de llegar al rango técnico medido. No elevar la cuota global para ocultarlo: revisar equidad por identidad autenticada conservando protección anónima y de coste. No todas las rutas usan este guard: hay exenciones y admisiones durables específicas.

Otros presupuestos actuales (`services/api/src/auth-admission.ts:16–20`) incluyen 2000 challenges/h globales y 20000 operaciones/h para la categoría `account`. Son presupuestos por ventana, **no topes instantáneos en req/s**. Sus rutas no están representadas por el throughput de lecturas de este informe.

### Proxy con proveedor de 2 segundos

Cada fase duró 20 s y lanzó además una lectura autenticada por cada petición proxy ofrecida. El deadline del proxy siguió en 30 s: los rechazos se deben al cupo, no al timeout.

| Oferta al proxy | Proxy 200 | Proxy 503 por capacidad | Lecturas concurrentes 200 | p95 de todas las respuestas proxy ms |
|---:|---:|---:|---:|---:|
|1/s |20/20 |0 |20/20 |2043.45 |
|3/s |60/60 |0 |60/60 |2032.21 |
|5/s |91/100 |9 |100/100 |2037.84 |
|10/s |100/200 |100 |200/200 |2028.48 |

Las lecturas concurrentes conservaron p95 entre 16.6 y 23.4 ms. El gauge de proxy llegó a 10 en vuelo, coherente con la configuración; las operaciones rechazadas no generaron usage ni débito.

Aproximación para dimensionar: `llamadas simultáneas ≈ llegadas/s × duración media`. Con 10 slots y 2 s de proveedor, el techo ideal ronda 5/s **sin margen**; el resultado real de 5/s demuestra por qué no se debe operar sobre ese ideal. Con otro proveedor o latencia cambia el cálculo y siguen aplicándose sus cuotas de requests/tokens y el presupuesto de coste.

El proxy de tokens es no-streaming; **no confundir sus 10 slots con diez conversaciones de voz**. Voz conserva un propietario único y necesita su propia prueba.

### Integridad tras todo el experimento

- 200 cuentas al terminar; no hubo seed duplicado.
- 272 intentos settled y 272 filas usage: 1 del preflight y 271 completions aceptadas de carga.
- Cero intentos prepared/dispatched/unknown pendientes.
- Suma de saldos y suma del journal:ambas 199999997552 nanoUSD.
- El stack, sus volúmenes y su red se eliminaron; no hubo modificación del entorno persistente.

## 5. Cómo usar estas cifras sin inventar usuarios

El dato operativo es tráfico por tipo, no cuentas registradas. La conversión depende de lo que haga la app:

`peticiones/s = usuarios activos × peticiones por usuario y segundo`.

Ejemplo puramente aritmético: 1000 usuarios haciendo exactamente una lectura cada 5 s generan 200 lecturas/s. **No se ha probado que 1000 usuarios reales, 1000 logins simultáneos o 1000 sesiones de voz funcionen**, porque sus recorridos, duraciones, cuotas y datos serían otros.

Hasta disponer de telemetría real, usar 200/s de esta mezcla como referencia de laboratorio con margen respecto a la prueba corta de 400/s. No trasladar ese número a una configuración global ni a un SLA sin repetir con hardware/datos objetivo. Para la parte de proveedor simulado de 2 s, 3/s es el escalón completamente servido observado, no una promesa del proveedor real.

## 6. Mejoras futuras: cuándo y cómo

### A. Antes de prometer capacidad a usuarios

**Disparador:** se fija entorno de despliegue, comienza una campaña o cambia materialmente el patrón de tráfico.

1. Repetir con cuotas CPU/RAM explícitas, Caddy/TLS y datos sintéticos del tamaño/distribución previstos: sesiones, historial, saldos y registros de uso. No importar datos personales para obtener esa distribución.
2. Añadir perfiles de login/challenge, sincronización de progreso/uso y voz, no solo lecturas; separar cada política de admisión del throughput técnico.
3. Ejecutar al menos tres repeticiones del punto de operación y soak de 1–2 h; comparar memoria, cola, errores, recuperación y contabilidad. Detectar también si limita el generador; para tasas mayores usar varios emisores coordinados y agregar muestras, no promediar percentiles.
4. Acordar p95/p99 y proporción de rechazos aceptable por clase. Los 300/1000 ms de este experimento son criterios elegidos para explorar, no contrato de producto.

**Implementación preparada:** reutilizar `load/driver.mjs` y fixtures actuales; si se convierte el experimento en comando mantenido, añadir preset explícito al runner bajo SDD/TDD, con límites de duración/oferta y limpieza. No abrir destinos arbitrarios ni quitar el preflight. El harness usado aquí es un artefacto local ignorado, no un nuevo comando público instalado.

### B. Si la instancia se acerca a la presión observada en 800/s

**Disparador propuesto:** carga sostenida fuera del rango ensayado o combinación repetida de event-loop utilization>0.8, cola de pool sostenida y deterioro de p95/p99. Los umbrales son señales de investigación; la cola muestreada cada 15 s no ve todos los picos.

**Implementación:**
- Perfilar CPU/event loop en staging y tiempo de adquisición de conexiones; contrastar consultas dominantes y planes con datos representativos. No atribuir automáticamente el coste a logs, Fastify o SQL sin perfil.
- Añadir histograma de adquisición de pool con una abstracción tipada que cubra los caminos realmente usados; pruebas de éxito, agotamiento y liberación. No monkey-patchear un único overload y afirmar cobertura completa de pg.
- Aislar generación y retención en procesos/pools propios antes de que compitan con peticiones. Parametrizar tamaño de pool con validación y presupuesto total de todas las instancias, trabajadores y reserva operativa.
- Escalar HTTP solo después de separar las responsabilidades stateful. No replicar el main actual con voz activada: el lock de líder y conexiones locales impiden un escalado transparente. Mantener un worker de voz explícito y comunicación durable para sus comandos; no desactivar la funcionalidad sin sustituir esa integración.

**Aceptación:** repetir esta mezcla, añadir jobs/panel activos y caída/reinicio de worker; demostrar p95/p99 objetivo, colas acotadas y ausencia de duplicación financiera. Más conexiones sin más capacidad CPU/BD no es una corrección suficiente. Detalle arquitectónico en M3/M7 del [informe original](informe-performance-api-2026-10-30.md).

### C. Si hay muchos 429 en usuarios legítimos detrás de NAT

**Implementación:** inventariar qué guard aplica por ruta y distinguir rechazo por red, cuenta y global en métricas de cardinalidad cerrada. Para rutas autenticadas, diseñar cuotas por cuenta verificada combinadas con protección por red y tope global; nunca confiar en un accountID de cabecera. Mantener límites anónimos y admisión antes de operaciones caras. Definir coherencia entre instancias antes de escalarlas.

**Aceptación:** cuentas distintas detrás de una red, una cuenta abusiva, redes distintas, cabeceras falsificadas y expiración de ventana; un cliente no debe agotar a otros ni obtener coste ilimitado. Revisar el contrato de Retry-After. La decisión de cuota es de producto/seguridad, no una optimización cosmética del benchmark.

### D. Si el proveedor limita las completions

**Implementación:** medir latencias/requests/tokens/coste reales cuando se autorice ese proveedor. Dimensionar `PROXY_MAX_IN_FLIGHT` junto a cuota por cuenta y presupuesto económico; conservar deadline, respuesta acotada, intentos durables y rechazo temprano. No crear una cola ilimitada para ocultar 503. Si hay varias réplicas, el cupo por proceso se suma: usar admisión distribuida o propietarios particionados antes de prometer un límite global.

**Aceptación:** proveedor 2/5/15 s, 429,timeout,disconnect y reintento; lecturas siguen saludables, el cupo se libera y ninguna incertidumbre se convierte en cargo o reenvío inventado. Aumentar 10 a 50 sin medir también multiplica exposición y trabajo externo pendiente.

### E. Solo si se demuestra coste dominante de consultas/catálogo

Optimizar el panel mediante TTL/single-flight, retención por lotes e índices respaldados por planes; añadir caché pública con versión/variante e invalidación de publicación/retirada. No usar caché para autorizar saldo o revocación. No hay en esta medición evidencia que obligue a introducir Redis, PgBouncer o índices nuevos. Preservar compatibilidad con advisory locks de sesión si se evalúa pooling externo. Ver M5/M6 del [informe original](informe-performance-api-2026-10-30.md).

## 7. Preparación operativa sin cambiar arquitectura ahora

- Mantener los cupos/deadlines/bytes actuales del proxy, documentados en [api-capacity.md](services/api/docs/api-capacity.md).
- Vigilar por separado tráfico útil, 429 de política, 503 de capacidad, 504, cola SQL, event loop, memoria y resultados unknown; no usar una media global dominada por healthz.
- Antes de aumentar recursos, conservar baseline con SHA, imagen, cuotas, datos y criterios. Si fallan límites por política, documentarlos antes de llamarlo saturación.
- Antes de cambiar pool, admisión, workers o liquidación: SDD y regresiones deterministas más PostgreSQL aislado; para concurrencia/finanzas, verificación independiente.
- Repetir el ensayo cuando cambien rutas calientes, instrumentación, versiones de runtime/BD, índices, cardinalidad o topología.

**Prioridad recomendada cuando haga falta crecer:** medición representativa y perfil→aislamiento de jobs/HTTP→admisión/propiedad multiinstancia→optimizaciones SQL/caché justificadas. No reescribir el backend ni distribuir voz por anticipación.

## 8. Evidencia y límites pendientes

Evidencia local ignorada en `openspec/changes/api-performance-hardening/evidence/`: `capacity-envelope.log`, `capacity-envelope.mjs` y `run-capacity-envelope.mjs`. El protocolo usa módulos existentes sin cambiar código de producto. Se validaron sintaxis del harness, identidad/lectura/proveedor de fixtures, respuestas por shape y contabilidad final; proceso de experimento exit 0. Los logs/raw metrics se conservan localmente, no se publican tokens.

Esta tarea **no** reejecutó la suite funcional completa: no hay cambios de runtime o configuración de producto. La suite 785/785 de la fase anterior sigue documentada como evidencia histórica, no como nueva ejecución. Sí se ejecutó tráfico real HTTP/PG sintético de este experimento.

Pendientes: repeticiones estadísticas, soak de horas, datos grandes, TLS/Caddy, login real, voz, coste/cuotas de proveedor real, fallos de infraestructura, HA/multiinstancia y validación de producción. Tampoco se impusieron nuevos límites globales HTTP ni se subieron los existentes.

**Conclusión:** la API tiene margen local útil para el perfil de lecturas medido, pero el proxy, las redes compartidas y la voz tienen restricciones distintas. Ya sabemos qué rango se ha demostrado y qué señales justifican la siguiente inversión, sin añadir complejidad sin necesidad.
