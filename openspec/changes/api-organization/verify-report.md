# Informe de verificación — organización de la API

Fecha: 2026-10-23
Rama: `analysis/api-organization`
Base de comparación: `a87f3ef`
Alcance: recomendaciones aplicables H1–H9 y fases 0–4 de `informe-organizacion-api-2026-10-23.md`.

## Resultado

La reorganización acordada está implementada en incrementos reversibles. La suite compilada completa, el smoke HTTP real y los controles aplicables pasan. No se modificaron migraciones, contratos públicos, recursos del build ni módulos estables por uniformidad. Las recomendaciones condicionadas se cierran más abajo con su evidencia y motivo.

## Cierre de hallazgos y fases

| Informe | Resultado y evidencia |
|---|---|
| H1 / fases 1–3 | Las rutas administrativas de intentos, generación/revisión de exámenes y configuración LLM se registran desde tres módulos cohesivos sobre la misma instancia Fastify. Los hooks y políticas transversales siguen en la raíz. Pruebas PostgreSQL y HTTP de los dominios afectados pasan. |
| H2 | Autenticación, origen/CSRF, admisión, límites, errores y observabilidad permanecen en el ensamblaje raíz. El smoke HTTP confirma que la ruta de administración rechaza una petición sin sesión. La suite comprueba los efectos persistidos de admisión. |
| H3 | Las proyecciones de estado/borradores viven en operaciones de administración de level-tests; auditoría reusable se separó del entrypoint CLI. Transacciones de publicación y lanzamiento asíncrono se conservaron. |
| H4 | Las rutas de generación/configuración reciben el entorno desde `createApp`; los valores dinámicos almacenados en BD siguen leyéndose por petición y conservan su precedencia. Prueba de dos instancias aisladas: valores independientes, sin mutar `process.env`. |
| H5 / fase 4 | El lifecycle tiene una frontera import-safe y pruebas de inicio, fallo parcial, trabajo pendiente y cierre idempotente. Se conservaron entrypoint y orden observado de arranque/cierre. |
| H6 / fase 0 | CI ejecuta `make test-api`; el stack efímero prueba API compilada por HTTP, readiness acotada, pricing público y rechazo administrativo. No requiere proveedor externo ni credenciales reales. |
| H7 | Se preservan `dist/src/main.js`, resolución de migraciones/operaciones/fixtures y nombres de CLI. Los tests compilados y el build de la imagen ejercitan estas rutas. No se tocaron migraciones. |
| H8 | El README aclara la correlación `X-Request-Id` frente al cuerpo JSON del handler común y documenta smoke, cleanup, retención y comandos actuales. Los informes históricos no se reescribieron. |
| H9 | Se aisló auditoría reusable y se hizo explícita la limpieza del stack de humo. Los cambios de legibilidad se limitaron a los bloques intervenidos; no se aplicó formateo masivo ni se introdujeron reglas nuevas. |

### Inventario de las rutas afectadas

Se compararon las declaraciones `app.<método>(<plantilla>)` del `app.ts` base (`a87f3ef`) con las del `app.ts` actual más los tres módulos extraídos. Resultado: **78 expresiones de método/ruta en la base y 78 en el candidato; cero eliminadas y cero añadidas**. Los registros que ya estaban delegados a helpers externos permanecieron sin cambios en ambos lados. Además, las pruebas de integración ejercitan los grupos trasladados y el smoke recorre la imagen compilada. Esta comparación acota el inventario a las declaraciones directas del ensamblaje y módulos afectados; no convierte `printRoutes()` en una interfaz contractual.

### Recomendaciones condicionadas no aplicadas

- **Progreso/contenido:** no se extrajo otro grupo. En `app.ts` son dos handlers de progreso (GET/POST) que ya delegan las operaciones a `learning-progress`; la concentración observada en los grupos administrativos no existe aquí. Añadir otra capa de registro no reduciría una responsabilidad mezclada demostrada.
- **Cuentas, compras y sesiones alojadas:** se mantienen en entregas separadas, tal como condiciona el informe. Esta reorganización no mostró un problema de organización que justifique mezclar sus invariantes financieros, de ownership o concurrencia con el trabajo de rutas de exámenes.
- **Carpetas por capacidad, nueva arquitectura de capas, imagen separada de pruebas y formatter/linter global:** no se aplicaron; el informe los califica de opcionales o explícitamente fuera de alcance y no hay evidencia de que mejoren el cambio concreto.

## Verificaciones ejecutadas

| Comando | Resultado observado |
|---|---|
| `npm --prefix services/api run check` | Correcto (`tsc --noEmit`). |
| `make check` | Correcto: 138 pruebas Python; exporters y contratos multiplataforma al día. Python emitió `ResourceWarning` por conexiones SQLite sin cerrar en tests; no falló el comando y no se modificaron esos tests no relacionados. |
| `make obs-check` | Correcto: Compose válido, 5 reglas Prometheus, tests de reglas correctos y Alertmanager válido. |
| `make test-api` | Correcto: build/arranque de imagen compilada, smoke HTTP y **634/634 pruebas**, 0 fallos, 0 skips; al final eliminó solo `looro-smoke` y su volumen. |
| Prueba de política de `make test-api` con Docker simulado | Antes: un fallo de tests dejaba el stack sin teardown. Después: fallo limpia; `KEEP_SMOKE_STACK=1` retiene; éxito limpia; fallo del cleanup propaga error; un valor inválido falla antes de ejecutar Docker. |
| `git diff --check` | Correcto. |

## Límites y riesgos restantes

- `npm run build` en el host no pudo escribir en `services/api/dist`, preexistente y propiedad de root. No se borró ni cambió ese directorio. El build oficial en Docker y el smoke compilado sí pasaron.
- El ejecutable `gentle-ai` no está disponible en este entorno. Por tanto no se ejecutó evaluación/revisión nativa RDD ni se afirma que exista aprobación; la verificación funcional anterior sí es observable.
- El cambio es de organización y testabilidad, no una garantía nueva sobre proveedores o despliegue. Los límites externos ya señalados en el informe original permanecen fuera de esta entrega.

## Commits de trabajo

- `13f1be6` — smoke compilado, CI y documentación de errores.
- `159156a` — extracción de rutas de intentos.
- `072512b` — rutas administrativas restantes y operaciones de estado/borradores.
- `2674ff0` — entorno explícito, rutas de configuración LLM y auditoría.
- `eb70690` — lifecycle aislado y pruebas de ejecución.
- Pendiente al crear este informe: commit de cierre del harness y documentación.
