# Primera fase de endurecimiento de capacidad de la API

## Objetivo
Implementar incrementalmente las mejoras justificadas del informe `informe-performance-api-2026-10-30.md`: medición fiable, señales mínimas de saturación y trabajo acotado del proxy de tokens. El usuario aprobó este enfoque antes de abordar optimizaciones condicionadas.

## Alcance propuesto
- Corregir y probar el driver de carga; ejecución segura solo contra fixtures aisladas y proveedor local falso.
- Añadir señales de proceso y proxy sin PII y conservar RED y métricas actuales de pool.
- Limitar concurrencia total, duración y respuesta del proxy; conservar facturación y registrar desenlaces externos inciertos.
- Preparar una baseline local reproducible, sin afirmar capacidad de producción.

## Fuera de alcance
Separar workers, cambiar watchdog/locks financieros, Redis, PgBouncer, caché/CDN, índices, réplica de PostgreSQL y despliegue multiinstancia. Se decidirán a partir de la baseline. Sin llamadas reales a proveedores, ejecución remota, push ni PR.

## Estado
Primera fase implementada y verificada localmente; ver `verify-report.md` para pruebas, resultados y límites. Rama `feat/api-performance-hardening`. Artefactos previos no relacionados se preservan.
