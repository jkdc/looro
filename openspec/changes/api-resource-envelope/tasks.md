# Estado de ejecución

Diseño y plan aprobados mediante «sí»/«si». Ejecución directa en curso; sin despliegue ni cambios remotos.

- [x] T1: perfiles conjuntos, sampler y stack representativo; 6 pruebas focused PASS, type-check PASS, arranque economy con 8 residentes/cuotas efectivas/TLS verificado y limpieza PASS.
- [x] T2: cohortes nuevas, contratos de carga y TLS local; 20 pruebas focused PASS, type-check PASS y smoke PG/TLS con 1201 cuentas, 20 primeras concesiones, progreso persistido y examen real.
- [x] T3: runner acotado, resistencia y fallos; smokes24/24 por los tres perfiles, recuperación API/BD y proveedor degradado verificadas; corregido crash idlePool. Suite API completa y revisión independiente cerradas en T6.
- [x] T4: comparación, soak y decisiones CASE/Redis; perfil V4 validado a 50 RPS/1h en TLS interno, informe y suites completas PASS; commit local al cierre de unidad.
- [x] T5: añadir perfil `android-session-smoke` de siete rutas de una sesión foreground asumida y conservar `mobile-smoke`. RED/GREEN; 18/18 tests, type-check, suite API 839/839 y `git diff --check` PASS. Stack TLS: probe siete rutas + evento persistido PASS; 750/750 útiles a 50 RPS/15 s, p95 14,33 ms, p99 35,38 ms, integridad y cleanup PASS. Commit `a96d81f` (`feat(load): add Android session route profile`).
- [x] T6: revisión independiente y recheck dirigido de los dos hallazgos; contratos malformados rechazados, `mobile-smoke` medido con evidencia y mezcla hipotética claramente delimitada; suite completa835/835 PASS, type-check/diff-check PASS. Cierre local, sin despliegue ni remoto.
