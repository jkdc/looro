# Validar capacidad con presupuesto compartido de servidor

Estado: diseño aprobado por el usuario mediante «sí»; plan pendiente de revisión antes de implementar. Fecha: 2026-10-30.
Rama existente: `feat/api-performance-hardening`; base de referencia `2b61585`. Flujo Superpowers/SDD, ejecución directa con revisión independiente final.

## Resultado buscado

Resolver los puntos 1–4 pendientes, sin despliegue, y después analizar el uso de API que genera la app móvil. El servidor objetivo tiene 4 vCPU, 16 GB RAM, 200 GB NVMe y 16 TB de transferencia; Ubuntu y Coolify conviven con otros servicios. No equiparar el host local al servidor ni convertir transferencia mensual en rendimiento instantáneo.

El usuario confirma que el cuarto o mitad de recursos son presupuestos conjuntos de API, PostgreSQL y auxiliares, y que el panel administrativo puede usarse bajo demanda. Redis es una alternativa condicionada a evidencia, no un requisito de instalación.

## Enfoque elegido y alternativas

Se conserva el enfoque incremental aprobado: ensayos locales aislados con recursos acotados y comparación antes/después, reutilizando Node/Fastify/PostgreSQL y el driver actual. Primero medir; cambiar producto solo si un resultado reproducible lo justifica.

Distribuir servicios o añadir Redis de entrada aumenta complejidad y memoria sin un cuello de botella demostrado; queda descartado como punto de partida. Ensayar directamente en el servidor requeriría acceso y autorización remota que no se han concedido; queda fuera de esta fase.

## Presupuestos de ensayo

| Perfil | CPU conjunta máxima | RAM conjunta máxima | Interpretación |
|---|---:|---:|---|
| Económico | 1 vCPU | 4 GB | Cuarto del servidor |
| Intermedio | 2 vCPU | 8 GB | Mitad del servidor |
| Pico experimental | 4 vCPU | 12 GB | Compite por toda la CPU; deja memoria para el host |

Asignar límites explícitos por contenedor cuya suma no exceda el presupuesto del perfil; inspeccionar y registrar límites efectivos, CPU, throttling, RSS/cgroup, OOM, colas, p95/p99 y tasa útil. La distribución interna concreta queda al implementador y se documenta antes de medir; no prometer que Docker preste recursos automáticamente entre contenedores con límites duros. El perfil pico es un ensayo manual, no autoscaling ni reserva garantizada frente a otros servicios.

Contar API, PostgreSQL, edge y auxiliares activos de observabilidad dentro del presupuesto. Driver, stub y generadores de fixtures representan carga externa: ejecutarlos fuera del presupuesto de servicio, registrar su coste y limitaciones. No sustituir toda la observabilidad por un stub y llamarlo huella completa del servicio. Medir componentes opcionales por separado si se apagan; indicar exactamente qué queda activo en cada resultado.

Caddy/TLS se incorpora en red local con certificados de prueba, sin DNS público ni ACME externo; conservar un control directo para separar su coste. Los límites locales no reproducen exactamente vCPU compartidas, NVMe o red del destino. Dimensionar retención/crecimiento a partir de bytes medidos; no ocupar 200 GB ni consumir transferencia real para simular el servidor.

## Orden de ejecución y aceptación

1. **Reevaluar el último CASE.** Comparar la base anterior `aa51066` con `2b61585` usando perfiles libre reclamado, guest/activo y candidato nuevo. Tres repeticiones intercaladas bajo cuotas iguales. Mantener visibles tanto el ahorro SQL como la regresión p95 previa. Si no hay beneficio representativo, preferir restaurar el precheck anterior o una alternativa pequeña probada antes que sostener la optimización por número de consultas. No ajustar contabilidad, políticas o admisión para mejorar el benchmark.
2. **Ráfagas y carga representativa.** Cuentas nuevas sin grant, sincronización soportada y exámenes, además de mezcla de lecturas; validar shape y efectos persistidos. Recorrer tasas hasta incumplir criterios exploratorios existentes, separando 429 de política, 503 de capacidad y llegadas no emitidas. Login/proveedores externos solo con fixtures, sin credenciales reales. No simular endpoints que no existan ni presentar un stub como flujo completo real.
3. **Resistencia y fallos.** Tras seleccionar un punto sostenible, ejecutar un soak de al menos 1 hora en el perfil de operación recomendado; repetir escalones relevantes en los otros presupuestos. Dar un pronóstico de duración/coste antes del ensayo largo. Inyectar fallos acotados de proveedor y reinicio de API/BD en stacks sintéticos; comprobar recuperación, cola acotada, recursos liberados y contabilidad/estados inciertos sin cargos o reintentos inventados. No repetir por sistema soaks de horas para todas las combinaciones.
4. **Informe operativo.** Tabla de capacidad observada por presupuesto/mezcla, margen, dependencias activas, límites del generador y disco/red estimados. Guía para configurar después en Coolify, marcada no ejecutada/no validada en destino. Sin tocar despliegue, DNS, secretos, dependencias de producción, push, PR o merge.
5. **Análisis móvil posterior.** Leer Android e iOS con evidencia de rutas, disparadores, caché local, sincronización, examen, voz, reintentos, polling y ciclo de vida. No asumir que la app es mayoritariamente offline: verificarlo. Derivar escenarios ligero/típico/intensivo con peticiones/sesión, ráfagas y bytes cuando se puedan medir. Distinguir comportamiento del código, supuestos de frecuencia y uso real no observado. Estimar usuarios activos con esos supuestos, no convertir directamente RPS en usuarios registrados. Producir informe y contrastar la mezcla del ensayo; si resulta distinta, realizar una validación acotada de esa mezcla. No modificar la app automáticamente.

## Panel bajo demanda y Redis

Hoy `services/api/compose.yaml` monta `admin-ui` en Caddy; `Caddyfile` sirve estáticos y proxy de `/v1/admin/*` hacia la misma API. No hay un contenedor independiente de frontend administrativo que apagar. No parar Caddy ni la API para apagar el panel. Documentar qué puede realmente desactivarse y medir el ahorro; no crear un servicio solo para poder apagarlo. Jobs de generación y herramientas auxiliares deben distinguirse del panel estático: cerrar su web no prueba que sus jobs se hayan detenido.

Mantener métricas y señales necesarias durante ensayos, aunque dashboards visuales opcionales no estén activos. No cambiar contratos de autenticación o flags de admin en esta fase solo para ganar RAM.

Evaluar Redis con evidencia de consultas repetidas cacheables, presupuesto de RAM y semántica de invalidación. Si el perfil no lo justifica, cerrar la evaluación documentando que no se incorpora. Si lo justifica, describir clave/TTL/invalidación y fallback, usar prueba comparativa aislada con tests-first antes de integrar; nunca cachear autorizaciones de saldo/revocación ni añadir dependencia de producción sin revisar su diseño específico. No reutilizar un cache hit sintético como ahorro de toda la API.

## Pruebas y límites de seguridad

Cambios de harness/config: tests deterministas RED→GREEN, validación de perfiles/rangos, presupuesto agregado, destino aislado, limpieza e informe de fallos. Código productivo: regresiones de comportamiento previo, PostgreSQL efímero, type-check, suite completa y verificador financiero/concurrencia independiente cuando aplique. Capturar SHA e imágenes congeladas, recursos efectivos y orden de ensayos; no mezclar suites/profilers con las comparaciones temporizadas.

Sin datos, tokens ni proveedores reales; sin SSH o acceso a Coolify. Mantener fixtures, raw y capturas en `evidence/` ignorado; solo resúmenes saneados versionados. Preservar archivos ajenos y rama actual, commits locales convencionales. El diseño y el plan no equivalen a una certificación de producción.

## Entregables y cierre

Mantener proposal, specs, plan, tasks y verify-report en este change antes/durante implementación; informes `informe-*.md` en raíz según convención. Actualizar referencias históricas que siguen llamando pendiente a cambios ya implementados, sin borrar sus mediciones originales. Cerrar cada resultado con pruebas observadas y límites explícitos. Cualquier prueba no ejecutable queda pendiente identificada, nunca PASS inventado.

## Addendum — perfil sintético Android (2026-10-31)

El usuario aclaró que, durante desarrollo, la mezcla debe derivarse de las rutas y usos visibles en el cliente Android. Se conserva el `mobile-smoke` existente como comprobación de seis contratos básicos y se añade un preset separado que modela una sesión foreground posterior al bootstrap, con siete acciones: consultar providers, cuenta y saldo; pedir una página de historial; subir progreso pendiente; cargar el examen híbrido; y enviar un evento de uso por lotes. Se usa una acción de cada tipo solo para validar una mezcla de rutas; no representa una frecuencia observada, concurrencia de usuarios ni un «usuario típico». Las rutas condicionales de compra, guest y voz alojada quedan fuera de este perfil base y deben seguir identificadas como otros escenarios.
