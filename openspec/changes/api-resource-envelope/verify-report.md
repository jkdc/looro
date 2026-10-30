# Verificación de capacidad por recursos

Estado: ejecución directa del plan aprobado; T1/T2 completadas, T3–T6 pendientes. Sin despliegue ni certificación de capacidad todavía.

## T1 — Presupuestos y observabilidad real

- Type-check inicial/final PASS. Dos módulos nuevos: perfiles conjuntos cerrados y sampler con identidad/cuotas efectivas y métricas ausentes explícitas.
- RED por módulos ausentes; pruebas de reglas ejecutadas directamente con Node: 4 perfiles/config + 2 sampler PASS. Regresión de clave heredada observada 2 PASS/1 FAIL antes de corregir catálogo; confianza TLS observada 3 PASS/1 FAIL antes de separar la CA pública.
- Stack economy real: 8 residentes con suma de CPU = 1 vCPU y límites RAM = 3840 MiB (presupuesto 4096 MiB); Docker inspect contrastado con catálogo. API+PostgreSQL+Caddy+collector+Prometheus+Tempo+Alertmanager+sink activos, sin Grafana/panel. Driver/stub fuera del presupuesto de servicio.
- Fetch HTTPS con CA interna confiada por Node PASS; sin desactivar verificación TLS. El driver solo recibe el certificado público por volumen distinto al de las claves privadas. Trust/migración/seed son transitorios, no carga residente medida.
- Primer smoke falló sin diagnóstico completo; segundo localizó Permission denied al montar almacenamiento privado de Caddy. Se corrigió compartiendo solo la CA pública mediante transitorio; smoke final PASS y limpieza de todos sus recursos observada. No afecta configuración productiva.
- Collector distroless no ofrece cat/cpu.stat: throttling null, no cero. OOM false y reinicios 0 en la muestra. Son observaciones de arranque/health, NO rendimiento bajo carga.
- El runner host `node --test` mostró wrappers de archivo en vez de nombres de assertions; se ejecutaron tests TS directamente para observar cada prueba real. La suite compilada en Docker se verificará en el cierre.

Evidencia raw sintética ignorada: `evidence/t1-smoke*.log`; las mediciones temporales futuras deben ejecutarse sin suites/profilers concurrentes.

## T2 — Cohortes y flujos útiles

- RED inicial: driver 15 PASS/2 FAIL (TLS cerrado y POST progreso202), módulo nuevo ausente. El test con examen compuesto real detectó que weekId es numérico, no string; corrección validada 2 PASS/1 FAIL→3 PASS. Focused final: 3 workloads + 17 driver PASS; type-check PASS.
- Fixture resource de 1201 cuentas: probe1, reclamables180, guest10, activos10, nuevas1000. Preflight usa probe y no modifica grants nuevos; modo previo mantiene200 cuentas y sesiones4h, resource8h. Cohorte nueva se agota explícitamente, no recicla créditos reclamados.
- Integración contra API/PG reales: HTTPS confiado PASS, CA no confiable rechazada, preflight sin grants nuevos, 20 primeras concesiones, guest/activo sin crédito, POST202 de progreso con readback y examen híbrido determinista real. Saldos y diarios comprobados por probe; limpieza PASS. El probe versionado conserva assertions, no imprime tokens.
- Diagnóstico de empaquetado: `compose build api-load` devolvía0 sin construir porque ese servicio solo define imagen; el build está en migrate-load. Se corrigió el comando y construyó el código real. El smoke anterior de T1 validaba cuotas/TLS con una imagen previa, no el nuevo workload ni rendimiento.
- Un arranque con migrador CLI falló con mensaje genérico; diagnóstico de migración aislada PASS y arranque completo con comando diagnóstico de la misma función PASS, sin modificar migraciones ni timeout. No se afirma una causa raíz no capturada; el runner deberá registrar build/digest y validar arranque de nuevo. Ningún fallo o intento anterior se presenta como carga exitosa.
- Bytes del driver son cuerpos de petición/respuesta, no cabeceras/TLS ni cuota de transferencia total.

Sigue pendiente escalera por presupuesto, soak, fallos, comparación CASE/Redis y análisis móvil.
