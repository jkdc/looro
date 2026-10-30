# Tareas: capacidad API

Primera fase implementada en feat/api-performance-hardening. Diseño y ejecución directa aprobados; detalles y límites en verify-report.md.

- [x] Crear rama y confirmar enfoque incremental.
- [x] Preparar proposal, design y specs.
- [x] Revisión independiente del diseño y aprobación del usuario («continua»).
- [x] Escribir/autorrevisar plan y aprobar ejecución directa («si continua»).
- [x] T0: type-check inicial y registro de estado.
- [x] S1/S2, T1/T2: driver probado y fixtures aisladas; c255b03, correcciones5a5126b/21e2395; smoke/baseline con población verificada200.
- [x] S3, T3: métricas y documentación; a58ab51; OTel y obs-check PASS.
- [x] S5, T4: intentos y finalización SQL; aa5a67e; concurrencia/rollback PASS.
- [x] S4/S5, T5: límites/deadlines/cancelación/shutdown;30105cf/5a5126b; pruebas PG y sockets PASS.
- [x] S6, T6:785 pruebas de suite completas, pruebas adicionales explícitas, revisión y recheck independientes, baseline fast y slow/hang; informe actualizado.

## Alcance diferido
Sin despliegue, push o PR. Sin certificación de producción, soak, comparación before/after cuantitativa, escalado horizontal de voz ni infraestructura distribuida. No se marcan como ejecutadas esas recomendaciones condicionales del informe.
