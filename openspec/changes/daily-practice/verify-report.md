# Verificación — daily-practice

Fecha: 2026-11-07. **Se verifica documentación, no una funcionalidad implementada.**

## Comprobaciones de esta fase

| Comprobación | Estado |
|---|---|
| Código actual inspeccionado con CodeGraph y spot check | Ejecutado: plan diario, escenarios, auth hospedada, Words y fronteras de persistencia |
| `openspec validate daily-practice --strict --no-interactive --json` | Ejecutado: valid true, 0 issues; comprobación final repetida tras correcciones |
| Enlaces locales, estructura y whitespace de documentos nuevos | Ejecutado: enlaces presentes, fences cerrados y sin trailing whitespace |
| Directorio de evidencias ignorado | Ejecutado con git check-ignore sobre evidence/admin/example.png |
| Builder check independiente del conjunto documental | Ejecutado; dos ambigüedades corregidas y recheck acotado sin bloqueos pendientes |
| Cambios locales previos conservados | Sin editar ficheros preexistentes durante esta fase |

### Hallazgos del builder check y corrección documental

1. Un evento inmutable rechazado por CAS necesitaba una salida distinta del
   replay de resultado desconocido. Se separó intención inmutable y precondición
   de transporte; un 409 confirmado admite un único rebase compatible con el
   mismo eventId, nunca sobrescribir una etapa ya resuelta ni duplicar tiempo.
2. Omitir práctica tras speaking dejaba sin definir el sellado oral. Tanto enviar
   como omitir práctica sellan la evidencia aceptada; el cierre por tiempo queda
   parcial sin fingir que no hubo conversación.

Ambas correcciones tienen escenarios normativos y tareas de regresión. El
revisor volvió a comprobar solo esos dos hallazgos y confirmó su resolución;
esto es revisión del diseño, no prueba funcional ni garantía de ausencia de bugs.

## Lo que NO se ha ejecutado

No hay migraciones aplicadas, tests nuevos de producto, build Android/iOS,
integración PostgreSQL, E2E, llamadas a LLM/TTS reales, piloto, despliegue ni
activación. Tampoco commits, push o PR. Validación OpenSpec solo comprueba la
forma documental, no la corrección de la arquitectura ni su implementación.

## Comandos previstos para implementación

No ejecutar esta tabla como parte de una mera aprobación de documentos. Los
ficheros nuevos de tests de tasks.md aún no existen; primero deben demostrar RED.

| Área | Comando | Qué acredita / límite |
|---|---|---|
| TypeScript temprano y final | `cd services/api && npm run check` | Tipos; no flujo real ni BD |
| API rápida | `cd services/api && npm run test:unit` | Node tests; suite parcial puede omitir tests sin BD |
| API con PG efímero | `make test-api` | Integración real aislada; inspeccionar que tests diarios SQL se ejecutan, no skips |
| Contratos/schema/exporters | `make check` | Paridad y drift de artefactos, no duración real |
| Android temprano | `cd apps/android && ./gradlew :app:compileDebugKotlin` | Compilación temprana |
| Android core | `cd apps/android && ./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` | Lógica determinista y regresiones |
| Android lint/build | `cd apps/android && ./gradlew :app:lintDebug :app:assembleDebug` | Recursos/lint/APK |
| Android UI | `cd apps/android && ./gradlew :app:connectedUiTest` | Dispositivo/emulador y permisos/flujos reales |
| Swift Core | `swift test --package-path apps/ios` | Core donde haya toolchain compatible; no verifica App SwiftUI |
| iOS App/UI | `make ios-build` y `make ios-test` | macOS/Xcode y simulador configurado requeridos |
| Admin browser | `E2E_EVIDENCE_DIR="$PWD/openspec/changes/daily-practice/evidence/admin" node services/api/tests/browser/admin-e2e.mjs` | Requiere stack aislado/fixtures/stub/navegador según README de browser; no arranca ni descarga esos recursos por sí solo |
| Observabilidad | `make obs-check` si se tocan configs | Configuración; no implica métricas recibidas |

El runner E2E admite `E2E_PANEL_ORIGIN` y `E2E_API_ORIGIN`; apuntar solo al stack
aislado autorizado. No usar por defecto un entorno con datos reales. Para ejecutar
un test Node diario enfocado, tras crearlo: `cd services/api && node --import tsx
--test --test-concurrency=1 tests/daily-content.test.ts`; repetir el mismo comando
antes/después de implementar para demostrar RED/GREEN.

## Matriz mínima de evidencia final

| Flujo | Unit/core | SQL/API | UI/E2E | Piloto |
|---|---|---|---|---|
| Paquete igual entre alumnos, solo en | Requerido | Requerido | Requerido | — |
| Ayudas y dificultad accesible | Requerido | Flags/registro | Requerido | Requerido |
| Estimación de 15–20 min sin límite | Medición/analytics | Sin cierre por reloj | Requerido | Requerido |
| Topic presente en conversación | Política | Binding real | Ambos móviles | Requerido |
| Retomar el mismo intento en cualquier momento tras cierre | Reducer/outbox | Reinicios reales | Ambos móviles | — |
| Citas verificadas e incertidumbre | Requerido | Snapshot/review | Representación | Calidad autorizada |
| Words sin duplicar/borrar progreso | Importador/backup | SQLite real | Ambos móviles | — |
| Owner/idioma/restore/respuestas tardías | Requerido | Requerido | Ambos móviles y admin | — |
| Publicar/retirar/unknown/costes | Requerido | Requerido | Admin real | — |

## Autorizaciones pendientes

Revisión del diseño escrito y de los defaults explícitos del README; después
plan de ejecución y autorización de implementación. Uso de proveedores reales,
piloto con alumnos y despliegue siguen siendo decisiones independientes.
