# Organización de la API: diagnóstico y refactorización incremental

**Fecha:** 23 de octubre de 2026 · **Base analizada:** `a0997ae` (`feature/learning-platform`).

**Rama del informe:** `analysis/api-organization`.

**Alcance:** organización de `services/api`, mantenibilidad y protección del comportamiento. Este informe propone trabajo futuro: no implementa una refactorización ni cambia contratos.

## 1. Conclusión ejecutiva

**La API tiene una base técnica sólida y no necesita una reescritura ni una arquitectura nueva.** Ya separa buena parte del negocio por capacidades, permite construir HTTP mediante dependencias inyectadas y hace explícitas las transacciones. La inversión razonable está en reducir responsabilidades concentradas en el ensamblaje HTTP y reforzar las garantías con las que se moverían esas responsabilidades.

El principal problema no es que haya archivos largos o una carpeta `src` plana. Es que **registrar una ruta, clasificar su política de acceso y ensamblar sus dependencias exige coordinar varios lugares**, y algunas operaciones de aplicación siguen mezcladas con HTTP. Un cambio aparentemente organizativo puede alterar seguridad, auditoría, configuración o arranque si se limita a trasladar handlers.

### Recomendación

1. Preparar una matriz de contratos y una comprobación pequeña del servidor compilado.
2. Extraer un grupo acotado de rutas, conservando literalmente su comportamiento.
3. Evaluar el resultado antes de seguir. Solo después, extraer operaciones concretas y mejorar configuración/lifecycle donde aporte pruebas o independencia reales.

**No recomiendo** microservicios, un ORM, un contenedor de inyección, una reestructuración masiva en capas, ni mover todos los archivos para conseguir simetría visual.

### Prioridades

| Prioridad | Actuación | Motivo | Cuándo |
|---|---|---|---|
| Alta | Matriz de rutas/políticas y pruebas de caracterización del primer dominio | La seguridad transversal está vinculada al registro y a plantillas de rutas | Antes de extraer rutas |
| Alta | Smoke explícito del servidor compilado en la imagen endurecida | Las pruebas por inyección no demuestran por sí solas el ensamblaje de `main.ts` | Antes de reorganizar ensamblaje o rutas |
| Alta | Piloto de extracción HTTP | Reducir la mezcla de capacidades y aclarar responsabilidades | Tras las dos garantías anteriores |
| Media | Extraer operaciones de aplicación identificadas | Separar decisiones/SQL de adaptación HTTP, sin crear capas vacías | Solo en las capacidades intervenidas |
| Media | Configuración explícita y lifecycle comprobable | Reducir dependencia del entorno global y probar construcción/cierre | Después del piloto; alcance independiente |
| Media | Alinear documentación de entrada | Evitar contratos contradictorios y facilitar navegación | Preparación y cierre de cada fase |
| Baja | Separar auditoría reutilizable de CLI; mejorar teardown de pruebas | Aclarar dependencias y mantenimiento local | Cuando se toque esa zona |
| Condicionada | Agrupar más módulos por capacidad o separar imagen de pruebas | Útil si el crecimiento lo justifica, no como requisito de orden | Reevaluar con evidencia |

## 2. Alcance, método y límites

### Qué se ha examinado

- Composición HTTP, autenticación/admisión, parsers, errores y telemetría.
- Relaciones entre módulos, lógica de aplicación en handlers y límites de transacciones.
- Ensamblaje de servicios, configuración, workers y cierre del proceso.
- Pruebas, TypeScript, Docker, migraciones, comandos de operación y CI.
- Documentación de entrada y coherencia con el comportamiento actual.

Se realizaron dos revisiones de lectura complementarias —arquitectura y garantías de verificación/operación— y una comprobación de evidencias por el autor del informe. CodeGraph no estaba accesible mediante CLI ni MCP; se usaron búsquedas y lecturas acotadas del repositorio. No se ha supuesto que su índice local estuviera actualizado.

### Qué no demuestra este informe

No es una auditoría exhaustiva de seguridad, una medición de rendimiento en producción ni una certificación de todos los contratos. No se accedió a secretos, servicios desplegados ni proveedores externos. **No se han reproducido defectos funcionales nuevos durante este análisis**: los hallazgos se clasifican como riesgos de mantenibilidad, cobertura o documentación salvo indicación expresa.

Una refactorización nunca puede prometer riesgo cero. Aquí se propone reducirlo mediante equivalencia observable, entregas reversibles y comprobaciones dirigidas a las fronteras que se muevan.

### Evidencia de pruebas disponible

| Evidencia | Resultado | Alcance real |
|---|---|---|
| `tsc --noEmit`, ejecutado durante el análisis | Correcto | Consistencia de tipos; no acredita comportamiento |
| `tsx --test tests/http.test.ts tests/llm-config.test.ts tests/minute-commerce-config.test.ts`, durante el análisis | 21/21 correctas, sin omisiones | HTTP básico y configuración/proveedor; no toda la API |
| `make test-api`, inmediatamente antes del merge que dio lugar a la base | 627 correctas, 0 fallidas | Suite compilada con PostgreSQL efímero; no se repitió para redactar este documento |
| `make check`, en esa misma verificación previa | 138 pruebas Python y comprobaciones de contratos correctas | Contratos/exportadores compartidos; no sustituye pruebas HTTP |
| `make obs-check` y TypeScript después del merge | Correctos | Configuración/alertas y tipos de la base analizada |

El árbol del merge era idéntico al de la rama verificada. Estos resultados son una base útil, **no una prueba anticipada de futuros refactors**. Cada cambio deberá verificar su propio árbol y conservar los resultados/omisiones reales.

## 3. Mapa actual: qué organización existe ya

En el nivel principal de `services/api/src` hay 67 archivos TypeScript, incluido contenido generado. Existen 63 archivos `*.test.ts` en `services/api/tests`. `app.ts` tiene 1.036 líneas físicas. Son datos de contexto, no umbrales de calidad ni motivos suficientes para dividir archivos.

| Zona | Responsabilidad actual | Evaluación |
|---|---|---|
| `app.ts` | Factory HTTP, políticas comunes, registro de rutas y parte de la coordinación de negocio | Es la concentración más relevante que conviene reducir |
| `main.ts` | Lee entorno, construye servicios, conecta workers, escucha y cierre | Su papel de composition root es apropiado; conviene mejorar su verificabilidad |
| `auth*`, `admin-auth.ts`, `apple-revocation.ts` | Identidad, admisión y administración | Ya hay fronteras reconocibles; no reorganizar por estética |
| `minutes*`, `minute-*`, `subscriptions*`, `payments.ts`, `ledger.ts`, `pack-products.ts`, `ai-value-purchases.ts` | Saldos, compras, suscripciones y contabilidad | Mantener SQL e invariantes explícitos; cambios estructurales de mayor riesgo |
| `level-tests*`, `level-test-*`, `llm-config*` | Exámenes, intentos, publicación y proveedores | Capacidad identificable y candidata a una extracción HTTP limitada |
| `hosted-*`, `live-provider.ts`, `token-proxy.ts` | Sesiones/proveedores y contabilización asociada | Concurrencia y recuperación justifican prudencia, no una división inmediata |
| `db.ts`, `errors.ts`, `log.ts`, `metrics.ts`, `observability.ts` | Infraestructura transversal pequeña | Conservar sin crear una plataforma abstracta |
| `migrations`, `operations`, CLI | Esquema, privilegios y operación | Son contratos operativos, no archivos libres de mover |
| `tests`, Docker y CI | Verificación de dominio, HTTP, PostgreSQL y empaquetado | Cobertura valiosa; falta hacer explícita la comprobación de ensamblaje por red |

El proyecto se aproxima a un **monolito modular por capacidades**, aunque su presentación sea principalmente plana. No hace falta completar una plantilla de Clean Architecture para que esté bien organizado.

## 4. Buenas prácticas que ya existen y deben conservarse

### 4.1 Factory HTTP y dependencias explícitas

`createApp(services)` separa la construcción de Fastify de la escucha del servidor (`src/app.ts:55–65,92`). Esto permite pruebas por `app.inject()` sin iniciar un proceso completo. La extracción debe aprovechar esa frontera, no sustituirla por dependencias globales.

Existe incluso un precedente de registro separado: `registerLevelTestAttemptRoutes` se invoca desde `app.ts:252` y está definido en `level-test-attempts.ts:354–405`.

### 4.2 SQL y transacciones visibles

`transaction` entrega un único `PoolClient`, hace commit/rollback y lo libera (`db.ts:8–18`). Publicación de exámenes y generación tienen bloqueos explícitos (`level-tests-publication.ts:18–31`; `level-tests-generation-jobs.ts:50–65,103–108`). Esta visibilidad es valiosa: no debe perderse detrás de repositorios genéricos.

### 4.3 TypeScript estricto y pruebas que ejercitan invariantes

`tsconfig.json` habilita `strict` y `noUncheckedIndexedAccess`. Hay pruebas de migraciones concurrentes y recuperación (`tests/migrate.test.ts:37–115`), propiedad y límites HTTP (`tests/hosted-http.test.ts:28–85`), y cancelación/no reintento de proveedor (`tests/llm-config.test.ts:6–24`). No se deben sustituir por mocks de bajo nivel que únicamente comprueben llamadas.

### 4.4 Fronteras de proveedor y motores deterministas

`minute-commerce-config.ts:59–85` ofrece ensamblaje e inyección de transportes. El compositor de exámenes conserva un contrato determinista multiplataforma (`level-tests.ts:1–13`). Son patrones locales que conviene extender donde resuelvan una necesidad, no reemplazar por otro sistema de DI.

### 4.5 Seguridad y observabilidad tratadas como comportamiento

El parser conserva bytes crudos de webhooks firmados (`app.ts:114–117`). Los hooks y el manejador común de errores centralizan admisión y telemetría (`app.ts:120–251`). Sus detalles forman parte del contrato y deben entrar en las pruebas de una extracción.

## 5. Hallazgos y mejoras justificadas

### H1. El ensamblaje HTTP mezcla demasiadas responsabilidades — prioridad alta

**Evidencia:** `app.ts:120–251` instala políticas comunes; `:358–763` registra distintas capacidades administrativas; `:776–1034` combina identidad, progreso, comercio y conversación.

**Por qué importa:** una modificación localizada se revisa junto a políticas y capacidades que no debería afectar. Algunas rutas dependen de helpers y decisiones encerradas en la misma factory. El problema es la responsabilidad compartida, no el número de líneas.

**Cambio propuesto:** extraer funciones de registro por capacidad, con dependencias pequeñas y explícitas. Empezar por las rutas administrativas de exámenes, sin incluir a la vez toda administración, LLM, resultados, autenticación y facturación. Delimitar el piloto por las rutas que realmente se muevan y registrar la lista en su propuesta.

**Cómo hacerlo:** mantener `createApp` como API interna estable; instalar primero los mecanismos comunes en la raíz; llamar después a funciones de registro. Mantener rutas, métodos, opciones y handlers sin cambios semánticos en la primera entrega. Los módulos no deben importar `main.ts` ni iniciar workers al importarse.

**Evitar:** añadir un archivo por endpoint, pasar todo `Services` a todos los módulos, o crear un registro dinámico/autodescubrimiento. Un grupo cohesivo de rutas suele bastar.

**Prueba de valor:** se puede añadir o revisar una ruta del dominio sin editar handlers de otros dominios y sin duplicar admisión, errores o telemetría.

### H2. La política transversal está acoplada a plantillas de rutas — prioridad alta

**Evidencia:** conjuntos de rutas en `app.ts:66–71`; selección de admisión, exenciones y límites en `:130–173`; comprobaciones administrativas dentro de handlers (`:359–371`).

**Riesgo:** conservar el cuerpo de un handler no basta si su nuevo registro cambia la plantilla, el orden o el ámbito que aplica hooks. No se ha demostrado una ruta desprotegida en el estado actual.

**Cambio propuesto:** documentar y comprobar primero una matriz método/plantilla/política. Mantener inicialmente las políticas en la raíz. Separarlas en un módulo solo cuando permita probarlas o eliminar duplicación concreta; no cambiar simultáneamente su modelo.

**Excepciones que deben preservarse:** webhooks firmados sin trusted-proxy; rutas públicas fuera del bucket compartido; login/challenge administrativo; admisión específica de invitados; límites por ruta y servicios no configurados. No uniformar CSRF u ownership sin estudiar cada operación.

La documentación oficial explica que los plugins crean contextos de encapsulación y que su registro afecta a la disponibilidad de elementos del contexto. Por eso una función de registro sobre la misma instancia es una primera extracción más conservadora que convertir todo en plugins encapsulados. Véanse [encapsulación](https://fastify.dev/docs/latest/Reference/Encapsulation/) y [plugins de Fastify](https://fastify.dev/docs/latest/Reference/Plugins/). Es una recomendación para este refactor, no una objeción al uso de plugins.

### H3. Algunas operaciones de aplicación siguen dentro de HTTP — prioridad media-alta

**Evidencia:** resolución del tipo de compra y cuenta eliminada (`app.ts:94–109`); eliminación y auditoría (`:510–520`); planificación/lanzamiento de generación (`:550–587`); consultas y proyección de estado/borradores (`:629–680`).

**Cambio propuesto:** después de separar registros, extraer únicamente operaciones con significado propio. Por ejemplo: consultar estado de generación, listar borradores para revisión o resolver el estado de una compra. Recibir datos validados y devolver resultados explícitos; HTTP sigue siendo responsable del status y del formato público.

**Orden recomendado:** comenzar por una lectura administrativa acotada. Dejar la coordinación de compras y las escrituras auditadas para una entrega posterior, con pruebas de persistencia y propiedad. No combinar extraer rutas, cambiar SQL y rediseñar resultados en el mismo paso.

**Evitar:** crear `Controller → Service → Repository` para cada consulta. Si una función existente ya reúne validación y transacción de una operación, puede ser la frontera correcta (`progress.ts:29–70` ofrece un ejemplo). No introducir un mapper que cambie accidentalmente números, fechas, campos opcionales o códigos de error.

### H4. Configuración global dentro de rutas reduce el aislamiento — prioridad media

**Evidencia:** `main.ts:38–58` configura servicios, pero `app.ts:565,586,706,715,729` también utiliza `process.env` al gestionar generación y configuración LLM.

**Riesgo:** dos aplicaciones construidas con servicios diferentes pueden seguir compartiendo configuración implícita del proceso. Las pruebas deben modificar el entorno global y pueden volverse dependientes de su orden.

**Cambio propuesto:** al extraer ese dominio, inyectar su configuración o sus operaciones, siguiendo los factories locales existentes. No crear una estructura universal con todas las variables del monorepo.

**Restricción esencial:** distinguir configuración fija de arranque de la configuración LLM editable en BD. No convertir una lectura dinámica en un snapshot fijo. Preservar precedencias, valores por defecto, gates y comportamiento de fallo cerrado. Unificar validaciones para endurecerlas sería un cambio funcional separado.

**Aceptación:** probar dos configuraciones aisladas sin mutar `process.env` en los handlers; verificar que la edición de configuración dinámica conserva el efecto esperado y que los valores sensibles no aparecen en errores/logs.

### H5. El lifecycle es correcto como responsabilidad, pero difícil de probar aislado — prioridad media

**Evidencia:** `main.ts:26–32` crea BD/telemetría; `:38–115` construye capacidades; `:116–159` conecta scheduler, timers, señales, escucha y cierre.

**Cambio propuesto:** solo después del piloto HTTP, aislar la construcción y el ciclo de vida detrás de dependencias explícitas. `main.ts` puede seguir siendo el ejecutable delgado que conecta entorno y señales. El objetivo es comprobar arranque/cierre sin señales reales ni llamadas externas, no crear un framework de runtime.

**Detalles que no se deben alterar incidentalmente:** hoy hay timers y trabajo de mantenimiento antes de `listen`, mientras el runner comercial y el scheduler de generación se arrancan después (`main.ts:131–152`). La parada incluye intervalos, vuelos pendientes, scheduler, HTTP, proveedores, telemetría y BD (`:144–159`). Conservar el orden observado en la primera extracción; una mejora de ese orden requiere su propia decisión y pruebas.

No se afirma que exista actualmente una fuga, una carrera o un shutdown defectuoso. La oportunidad es hacer verificable esa frontera y sus caminos de fallo parcial.

### H6. La suite no acredita por sí sola el ensamblaje HTTP de la imagen — prioridad alta como prerrequisito

**Evidencia:** el smoke inicia una API endurecida, pero el servicio `tests` recibe únicamente `TEST_DATABASE_URL` y depende de PostgreSQL (`tests/compose.smoke.yaml:27–51`). Las pruebas HTTP construyen apps e inyectan peticiones (`tests/http.test.ts:19,67,92`). CI comprueba tipos/build/tests, sin construir y sondear esa imagen (`.github/workflows/checks.yml:51–54`).

**Interpretación correcta:** `make test-api` sí arranca el contenedor y prueba código compilado con BD real. Lo que falta es una aserción explícita, acotada y reproducible sobre la API escuchando y sus dependencias ensambladas por `main.ts`. No se debe describir esta carencia como ausencia de pruebas de integración.

**Cambio propuesto:** un smoke pequeño contra la API del stack efímero: espera limitada a readiness, ruta pública representativa, rechazo de una ruta protegida y cierre del proceso. No duplicar las cientos de pruebas por TCP ni requerir claves/proveedores reales. Fijar el perfil de configuración para conocer qué capacidades deben estar disponibles o deshabilitadas.

Para ejercitar una ruta de una capacidad que el perfil base deja deshabilitada, usar un perfil efímero autorizado con dependencias locales simuladas; un 503 esperado por feature apagada no verifica un camino feliz de esa capacidad.

### H7. Las rutas de archivos son contratos del build y la operación — prioridad media

**Evidencia:** TypeScript emite `dist/src` y `dist/tests` (`tsconfig.json:5–7`); `package.json:12` y `Dockerfile:10,17–21` esperan esa estructura. Migraciones y operaciones se copian a rutas concretas; `migrate.ts:11–14` resuelve recursos relativos al módulo; `tests/admin-cli.test.ts:32–38` distingue ejecutables fuente/compilados.

**Cambio propuesto:** mantener inicialmente layout de compilación, puntos de entrada CLI y ubicación de recursos. Si se mueve un módulo que resuelve archivos, probarlo desde fuente y desde JS compilado, con el cwd de ejecución real.

**Evitar:** cambiar `rootDir`, excluir tests del build, mover recursos, renombrar comandos y agrupar módulos en la misma entrega. Separar imagen de producto y pruebas puede valorarse después, pero no es un prerrequisito ni una vulnerabilidad demostrada.

No mover ni reescribir migraciones aplicadas. Una reorganización del código no debería requerir cambios de esquema ni de datos.

### H8. La documentación de entrada tiene deriva — prioridad media, bajo riesgo de corrección

**Evidencia:** `services/api/README.md:102` afirma que los errores solo llevan `error.code`; `:167–170` afirma que todos los 5xx llevan `error.requestId`. El contrato compatible actual distingue el manejador común y respuestas directas: la correlación `X-Request-Id` no implica añadir el campo JSON a todos los cuerpos (`app.ts:203–250` y rutas de prueba de proveedor en `:732–751`).

También conviven instrucciones actuales con verificaciones históricas de PostgreSQL 14 y cifras de pruebas antiguas. Su presencia histórica no es falsa, pero debe ser inequívoca y no confundirse con el entorno recomendado hoy.

**Cambio propuesto:** mantener un README de entrada con ejecución, pruebas con/sin BD, build, mapa breve y enlaces. Concentrar cada contrato en un documento canónico; enlazar en vez de copiar párrafos operativos. Conservar informes fechados como historia y marcar sus límites.

**Aceptación:** comandos y enlaces válidos; descripción de errores coincidente con código y pruebas; quien entra en la API encuentra cómo verificar un cambio sin recorrer informes históricos. No hace falta generar OpenAPI ni migrar toda la documentación para resolverlo.

### H9. Mejoras locales secundarias — prioridad baja o condicionada

| Observación | Mejora proporcionada | Lo que NO implica |
|---|---|---|
| Código reutilizable de auditoría se importa desde un módulo con nombre de CLI (`app.ts:32`, `level-tests-publication.ts:4`, `admins-admin.ts:76`) | Separar el helper de auditoría del punto de entrada cuando se modifique esa zona | Importar el módulo no ejecuta automáticamente su CLI: hay una guardia |
| `make test-api` solo llega a `down --volumes` si pasan pasos anteriores (`Makefile:79–82`) | Limpieza por defecto preservando el exit code, con retención explícita para investigar | No limpiar otros stacks ni volúmenes del usuario; retener al fallar también puede ser una política legítima si se documenta |
| Hay bloques densos y estilo desigual, por ejemplo el lifecycle | Mejorar legibilidad en bloques que se cambien y acordar formato mínimo | No reformatar todo ni introducir reglas masivas para dar apariencia de refactor |
| El código está organizado por nombres de capacidades en una carpeta plana | Agrupar solo cuando una capacidad extraída tenga varios módulos y la navegación mejore | Una carpeta plana no es por sí misma una mala práctica |

La relación entre `pack-products.ts` y `ai-value-purchases.ts` incluye una importación solo de tipos en un sentido. No hay evidencia aquí de un ciclo de ejecución que justifique una reparación urgente. No convertir una advertencia estática en un defecto sin verificarla.

## 6. Organización objetivo mínima, no una migración masiva

La primera meta puede ser tan pequeña como esta; las rutas son una propuesta para una implementación futura:

```text
services/api/
  src/
    app.ts                       # Factory y ensamblaje HTTP estable
    main.ts                      # Ejecutable y composición de servicios
    http/
      routes/
        admin-level-tests.ts     # Solo el grupo piloto
    level-tests-admin.ts         # Operaciones existentes; conservar entrada
    level-tests-publication.ts   # Transacciones/bloqueos existentes
    level-tests-generation-jobs.ts
    ...                          # Resto de módulos: sin movimientos por estética
  tests/
    ...                          # Mantener ubicación y ejecución actuales
```

- El módulo de rutas recibe solo sus dependencias. No importa el ejecutable ni localiza servicios globalmente.
- Las políticas comunes permanecen inicialmente en la factory. Extraerlas es otra decisión, posterior a sus pruebas.
- Las operaciones nuevas pueden convivir junto a los módulos actuales de la capacidad. No hay obligación de abrir `domain/application/infrastructure`.
- No crear `utils`, `common` o `shared` como destino genérico. Un helper compartido necesita una responsabilidad y consumidores concretos.
- No introducir barrels con exportaciones indiscriminadas para ocultar dependencias. Los imports deben seguir siendo trazables.
- Si después se agrupan archivos por capacidad, hacerlo por separado y preservando exports/puntos de entrada cuando otros módulos los consuman.

**Criterio de aceptación organizativo:** responsabilidades más claras, dependencias más estrechas y cambios de una capacidad más locales. Reducir líneas, mover un porcentaje de archivos o alcanzar un número de carpetas no son objetivos.

## 7. Fases propuestas

Estas fases no son tareas ya autorizadas para implementar. Cada una requiere definir su alcance y verificarlo; no se propone completar todas por inercia.

### Fase 0 — Contrato y red de seguridad

**Objetivo:** disponer de una base comparable antes de mover código.

1. Registrar commit y perfil de configuración de partida; ejecutar tipos, suite API efímera y comprobaciones pertinentes.
2. Inventariar métodos y plantillas registrados tras `app.ready()`, incluyendo rutas delegadas. Usar `onRoute` o mecanismo equivalente y normalizar orden/HEAD implícito. No depender de parsear texto de `printRoutes` como contrato estable.
3. Completar la matriz de políticas del piloto: acceso, ownership, origen/CSRF, admisión, límites, feature apagada, errores y caché. Añadir solo los casos que falten.
4. Añadir el smoke acotado de la imagen y corregir contradicciones documentales de los contratos afectados.

**Salida:** baseline reproducible; lista explícita de rutas a mover y resultados esperados; smoke asertivo. Cero omisiones inesperadas. Una prueba omitida por faltar BD no cuenta como equivalencia verificada.

**Riesgo:** las pruebas/CI pueden afectar al flujo de entrega; mantener ese cambio separado de la extracción. No introducir novedades de negocio.

### Fase 1 — Piloto HTTP reversible

**Objetivo:** separar registro de rutas sin alterar operaciones.

1. Extraer un grupo de administración de exámenes a una función sobre la misma instancia Fastify.
2. Pasar dependencias explícitas; conservar orden de registro y opciones por ruta.
3. Mantener SQL, auditoría, parser y hooks donde estaban si moverlos cambia el alcance del paso.
4. Mantener el `202` y el lanzamiento desacoplado de generación; no esperar a que termine el job para responder.

**Salida:** inventario idéntico; mismas respuestas/cabeceras y efectos; pruebas del dominio, suite PostgreSQL y smoke correctos. La entrega no debe incluir renombrados de API ni rediseño de DTO.

**Reversión:** commit cohesivo de extracción, sin migraciones, dependencias nuevas o cambios de datos. Revertir ese commit restaura la organización anterior sin deshacer información persistida.

**Punto de decisión:** continuar solo si la navegación y la revisión de cambios mejoran de forma visible. Si solo se ha cambiado de sitio la complejidad, ajustar o parar.

### Fase 2 — Operaciones de aplicación y configuración del piloto

**Objetivo:** retirar del HTTP las decisiones/consultas identificadas que realmente dificulten pruebas o reutilización.

1. Comenzar por una lectura administrativa concreta; mantener mapeo público.
2. Después, si se justifica, extraer un comando auditado manteniendo su transacción y cliente.
3. Sustituir dependencia implícita de `process.env` por configuración/operaciones del dominio, preservando lecturas dinámicas de BD.
4. Documentar quién posee la transacción y qué efectos se ejecutan fuera de ella.

**Salida:** pruebas del comportamiento anterior, rollback/auditoría/ownership y configuración; misma ruta/status/JSON. Ningún cambio de atomicidad, retries, timeout o serialización introducido como “limpieza”.

**Riesgo:** seguridad, persistencia o concurrencia según la operación. Las garantías actuales de bloqueo no se reemplazan por un mutex local ni por mocks. Un refactor de compra no se mezcla con el de exámenes.

### Fase 3 — Ampliación selectiva a otras rutas

**Objetivo:** aplicar lo aprendido solo donde exista una concentración comparable.

Orden orientativo: separar LLM/configuración administrativa como grupo propio; después valorar progreso/contenido. Cuenta, compras y sesiones alojadas quedan para entregas distintas, solo si siguen teniendo un problema real y con cobertura de sus invariantes.

**Salida:** cada capacidad gana una frontera útil sin agrandar un contenedor de dependencias global. Repetir controles de las fases 0–2 para el dominio concreto. No mover motores deterministas estables para uniformar nombres.

**Condición de parada:** dejar de extraer cuando el resto ya sea comprensible y los cambios habituales no crucen responsabilidades ajenas. Esta fase no implica que todas las rutas deban salir de `app.ts`.

### Fase 4 — Lifecycle y empaquetado, solo si sigue siendo necesario

**Objetivo:** comprobar construcción y cierre de recursos de forma aislada, sin rediseñar el runtime.

Separar parseo puro y creación con efectos únicamente donde haya una prueba que lo aproveche. Probar arranque normal, fallo de construcción parcial y parada con trabajo pendiente. Mantener puntos de entrada, orden y gating. El smoke del artefacto verifica la integración real de esas piezas.

La separación de imagen de pruebas/producto, un catálogo tipado de políticas o agrupaciones adicionales de directorios son posibles trabajos posteriores, **no parte obligatoria** de esta fase.

**Salida:** sin timers/recursos retenidos en los escenarios probados; ningún worker comienza antes de lo previsto ni sigue trabajando tras su parada; CLI y contenedor siguen resolviendo sus recursos.

## 8. Contratos que no se deben romper y cómo comprobarlos

| Frontera | Invariante | Comprobación proporcionada |
|---|---|---|
| Rutas | Mismos métodos y plantillas, sin rutas omitidas/duplicadas | Inventario normalizado antes/después y peticiones del grupo movido |
| Admisión/seguridad | Mismas excepciones públicas, acceso administrativo, ownership y límites | Matriz de casos positivos/negativos; los rechazos de admisión/autorización no invocan proveedores ni producen efectos de negocio, preservando los registros de límites y auditoría que correspondan |
| HTTP | Mismos status, códigos, campos opcionales, cabeceras y límites de cuerpo | `app.inject()` con aserciones semánticas, no snapshots de IDs/fechas aleatorios |
| Webhooks | Bytes firmados intactos, misma autenticación y deduplicación | Payload crudo válido/alterado y duplicados sobre BD real |
| Compras/auditoría | Misma unidad transaccional y efectos contables | Estado persistido, rollback y fallo de auditoría según la semántica existente |
| Publicación/generación | Mismos locks, exclusión, `busy`/`exists`, resultado y lanzamiento asíncrono | Pruebas concurrentes con PostgreSQL; no solo mocks |
| Configuración | Mismos defaults, precedencias, gates y cambios dinámicos | Configuraciones aisladas, BD frente a entorno, capacidad desactivada |
| Lifecycle | Mismo orden y liberación de recursos en escenarios cubiertos | Dependencias fake para ciclo aislado y smoke de proceso real |
| Artefacto | Mismas entradas CLI y resolución de recursos en compilado | Build, imagen endurecida y CLI afectadas; no ejecutar migraciones sobre datos del usuario |
| Telemetría | Plantilla de ruta segura, correlación, duraciones y resultados sin PII | Pruebas de observabilidad existentes y validación funcional de alertas |
| Multiplataforma | Composición/contratos compartidos intactos cuando se toquen | Golden tests/exportadores/checkers pertinentes; no regenerar contenido sin necesidad |

### Orden de verificación por entrega

1. Añadir primero la caracterización que falte y comprobar que describe el comportamiento de la base. Para una extracción pura, estas pruebas pueden estar verdes antes del cambio: **no inventar una fase RED** ni introducir un defecto para hacerlas fallar.
2. Ejecutar el refactor mínimo, sin cambiar reglas al mismo tiempo.
3. Ejecutar pruebas focalizadas del dominio, `tsc --noEmit` y `npm run build`.
4. Ejecutar `make test-api` sobre el stack efímero, comprobar resumen y omisiones, y el smoke de la imagen definido en fase 0.
5. Ejecutar `make obs-check` y `make check` cuando afecte a sus contratos; en el cierre del conjunto, ejecutar ambas para conservar la base integrada.
6. Revisar diff y contratos, no solo el indicador verde. Las fronteras de seguridad, transacciones y concurrencia requieren una revisión independiente.

No llamar a proveedores reales para comprobar una reorganización. No reutilizar credenciales de despliegue ni limpiar volúmenes de desarrollo. Las fixtures persistidas deben quedar aisladas.

## 9. Estrategia de entrega y reversión

- Una entrega de garantías, una de extracción mecánica y otra de operaciones si resulta necesaria. No mezclar movimientos con cambios funcionales o formato global.
- Si un import relativo, una ruta pública o un punto de entrada cambia, tratarlo como contrato: preservar compatibilidad o proponer ese cambio fuera del refactor.
- Mantener cada entrega revisable por su propósito. No usar un límite de líneas como razón para omitir pruebas o crear abstracciones innecesarias.
- Registrar los comandos/resultados y el commit exacto verificado. No aprobar por el número histórico de tests.
- Si falla una garantía: parar el dominio afectado, reproducir contra la base y corregir dentro de ese alcance o revertir la extracción. No acumular excepciones y continuar moviendo módulos.
- Mantener movimientos sin migraciones ni datos nuevos: permite revertir código sin necesitar una reversión de esquema.
- Si aparece un defecto preexistente al caracterizar, documentarlo y separar su corrección. Una mejora funcional no debe esconderse como equivalencia estructural.

## 10. Qué no haría y por qué

| Alternativa | Decisión |
|---|---|
| Reescribir Fastify o cambiar framework | No hay evidencia que lo justifique; aumenta superficie y coste de regresión |
| Imponer Clean/Hexagonal Architecture completa | Las dependencias ya son suficientemente explícitas para empezar; capas vacías añadirían navegación sin aislar mejor |
| Introducir ORM/repositorio genérico | Ocultaría consultas/locks sensibles y no resuelve la concentración HTTP |
| Microservicios/event bus/CQRS generalizado | No hay necesidad demostrada de despliegue o escala independiente |
| Mover los 67 módulos a carpetas de una vez | Mucho ruido de imports/build con poco aprendizaje; el piloto debe validar beneficio |
| Reescribir migraciones aplicadas o adaptar datos existentes | Fuera del objetivo; riesgo innecesario |
| Unificar todos los errores, schemas o serializadores | Puede cambiar contratos, validación y cuerpos firmados; evaluar por separado |
| Catalogar toda la configuración en una clase global | Reemplaza un global implícito por un objeto global mayor; preferir dependencias locales |
| Crear infraestructura compartida para un solo consumidor | Aplazar hasta una necesidad concreta |
| Activar lint/formatter masivo o actualizar dependencias a la vez | Mezcla ruido y cambios de comportamiento con la reorganización |

## 11. Decisión recomendada

**Aprobar únicamente la preparación y un piloto de extracción HTTP.** Evaluar su resultado antes de autorizar más trabajo. La prioridad es que resulte más fácil cambiar una capacidad sin conocer toda la API, manteniendo las garantías que ya existen.

No recomiendo comprometerse ahora con un refactor completo ni un calendario de todas las fases. Las fases 2–4 se justifican por los problemas que sigan existiendo después del piloto, no por completar una lista.

**Siguiente paso, si se decide implementar:** convertir las fases 0 y 1 en un cambio SDD acotado, enumerando rutas, matriz de contratos y pruebas de aceptación antes de modificar código. Este informe es el diagnóstico y la propuesta; no la autorización de esa implementación.
