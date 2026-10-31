# Estado de ejecución

Diseño y plan aprobados mediante «sí»/«si». Ejecución directa en curso; sin despliegue ni cambios remotos.

- [x] T1: perfiles conjuntos, sampler y stack representativo; 6 pruebas focused PASS, type-check PASS, arranque economy con 8 residentes/cuotas efectivas/TLS verificado y limpieza PASS.
- [x] T2: cohortes nuevas, contratos de carga y TLS local; 20 pruebas focused PASS, type-check PASS y smoke PG/TLS con 1201 cuentas, 20 primeras concesiones, progreso persistido y examen real.
- [x] T3: runner acotado, resistencia y fallos; smokes24/24 por los tres perfiles, recuperación API/BD y proveedor degradado verificadas; corregido crash idlePool. Suite API completa y revisión independiente final pendientes de T6.
- [x] T4: comparación, soak y decisiones CASE/Redis; perfil V4 validado a 50 RPS/1h en TLS interno, informe y suites completas PASS; commit local al cierre de unidad.
- [ ] T5: análisis móvil posterior y contraste de mezcla.
- [ ] T6: verificación independiente, informes y cierre local.
