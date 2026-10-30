# Verificación de capacidad por recursos

Estado: ejecución directa del plan aprobado; T1 completada, T2–T6 pendientes. Sin despliegue ni certificación de capacidad todavía.

## T1 — Presupuestos y observabilidad real

- Type-check inicial/final PASS. Dos módulos nuevos: perfiles conjuntos cerrados y sampler con identidad/cuotas efectivas y métricas ausentes explícitas.
- RED por módulos ausentes; pruebas de reglas ejecutadas directamente con Node: 4 perfiles/config + 2 sampler PASS. Regresión de clave heredada observada 2 PASS/1 FAIL antes de corregir catálogo; confianza TLS observada 3 PASS/1 FAIL antes de separar la CA pública.
- Stack economy real: 8 residentes con suma de CPU = 1 vCPU y límites RAM = 3840 MiB (presupuesto 4096 MiB); Docker inspect contrastado con catálogo. API+PostgreSQL+Caddy+collector+Prometheus+Tempo+Alertmanager+sink activos, sin Grafana/panel. Driver/stub fuera del presupuesto de servicio.
- Fetch HTTPS con CA interna confiada por Node PASS; sin desactivar verificación TLS. El driver solo recibe el certificado público por volumen distinto al de las claves privadas. Trust/migración/seed son transitorios, no carga residente medida.
- Primer smoke falló sin diagnóstico completo; segundo localizó Permission denied al montar almacenamiento privado de Caddy. Se corrigió compartiendo solo la CA pública mediante transitorio; smoke final PASS y limpieza de todos sus recursos observada. No afecta configuración productiva.
- Collector distroless no ofrece cat/cpu.stat: throttling null, no cero. OOM false y reinicios 0 en la muestra. Son observaciones de arranque/health, NO rendimiento bajo carga.
- El runner host `node --test` mostró wrappers de archivo en vez de nombres de assertions; se ejecutaron tests TS directamente para observar cada prueba real. La suite compilada en Docker se verificará en el cierre.

Evidencia raw sintética ignorada: `evidence/t1-smoke*.log`; las mediciones temporales futuras deben ejecutarse sin suites/profilers concurrentes.
