# Verificación: atajo de lectura drip

## Resultado
Implementado el cambio acotado propuesto tras el diagnóstico, base `360ab43`, en `feat/api-performance-hardening`. Seis líneas nuevas de producción; sin migraciones, caché, dependencias, cuotas ni cambios HTTP.

- Configuración se sigue leyendo de BD en cada llamada.
- Disabled/zero o concesión gratuita ya realizada no adquieren transacción de concesión.
- Ausencia de marcador conserva la transacción anterior, elegibilidad, presupuesto e INSERT ON CONFLICT. No se intenta conceder basándose en una lectura positiva.

## Pruebas observadas

| Prueba | Resultado |
|---|---|
| Type-check inicial y tras código/probes | PASS, Node 22 |
| Nueve regresiones nuevas sobre baseline | 6 PASS, 3 FAIL por transacción adquirida 1≠0 en disabled/zero/existing |
| Mismas nueve tras guard |9 PASS, 0 skipped |
| Nuevas + suite histórica subscriptions |22 PASS, 0 skipped |
| `make test-api` |**795 PASS, 0 skipped**, 326.8 s; PostgreSQL efímero |
| Once regresiones finales, incorporando probes del verificador |**11 PASS, 0 skipped**,PostgreSQL real |
| EXPLAIN del lookup |Index Scan using subscription_grants_period_idx; sin DDL |

Los dos probes adicionales (reservas/rollover y misses sincronizados) se incorporaron tras construir la imagen de la suite completa y se ejecutaron aparte. Son caracterización adicional; no se presenta una suite 797 como ejecutada ni se atribuye RED previo a todas las comprobaciones de preservación. Los 3 fallos RED iniciales sí prueban el cambio de trabajo transaccional.

Persistencia comprobada: saldo, grants y entradas iguales en retornos sin efecto; primera concesión única; cambio de mes UTC; marcador pagado/otra cuenta; guest/suscripción activa; presupuesto secuencial; política fresca; eliminación de marcador; configuración ausente y error SQL real. La carrera sincroniza dos lecturas que ven ausencia y luego exige [0, 5], una fila grant/entry y 300000 ms. Reservas y rollover conservan cálculo anterior.

## Revisión independiente

Snapshot scratch fuera del workspace. Sin bloqueantes causados por el cambio. El revisor reprodujo baseline 6 PASS/3 FAIL, candidato 9 PASS, probes 11 PASS, histórica 13 PASS y type-check sin errores. Verificó S1–S3 y comparación; la pendiente S4 de su dictamen se cierra con el readback del padre de la suite 795 y este informe.

No se amplía garantía de atomicidad del presupuesto global entre cuentas: el fallback no se modifica y las carreras preexistentes fuera del cambio quedan fuera de alcance. No hay semántica nueva de cancelación HTTP.

## Comparación antes/después

Imágenes construidas antes y después del único cambio de producción:

- Imagen baseline real: `a0a9458576f9fcac0d13a1816f258c240f32ea2782b190b4879d6ff15a795e6d`.
- Imagen candidate: `33ec13de2d03556fce445ebe4b02985702ddf11e369a4c129ae6f5de5e2c04e1`.

Tres repeticiones intercaladas B 1/A 1/A 2/B 2/B 3/A 3. Dos stacks propios inactivos cuando no se miden; un emisor activo, sin suite concurrente. PostgreSQL 17 con pg_stat_statements, OTEL habilitado, sin CPU profiler. Misma configuración/datos/recursos; 200 cuentas: 10 guest, 10 con suscripción activa, 180 libres (incluido preflight). Warmup 500 lecturas del conjunto libre. Pool 10, sin límites CPU/RAM explícitos, red interna sin TLS/Caddy. Reinicio de estadísticas SQL por fase.

Perfil libre: 70% saldo libre autenticado y 30% catálogo/health; 15 s por escalón 400/800. Controles guest y suscrito: 100/s durante 10 s. Deadline y cupo del generador conservados; llegadas perdidas no se cuentan como éxitos.

### Trabajo SQL, resultado determinista de las fases repetidas

| Ruta/perfil | Antes | Después | Coste |
|---|---:|---:|---|
| GET saldo libre con grant existente |11 sentencias |7 sentencias |4 menos (~36% de ese recorrido) |
| Transacciones/INSERT grant en esas lecturas |Una por lectura |Cero |Guard positivo |
| Guest sin marcador libre |9 sentencias |10 sentencias |Una consulta adicional |
| Suscrito sin marcador libre |10 sentencias |11 sentencias |Una consulta adicional |
| Primera concesión |Fallback original |Fallback+lookup |Consulta adicional; seguridad conservada |

Los totales pg_stat_statements incluyen consultas públicas y algunas de instrumentación. Las cifras por ruta se contrastan con el flujo y los conteos de autenticación/BEGIN/INSERT/lookup, no dividiendo sin más por todos los requests. No hay ahorro universal: si predominan invitados/suscritos sin marcador, el lookup añade trabajo. Antes de extrapolar, medir la proporción real de cada perfil; no se han inferido usuarios o distribución de producto.

### Latencia y throughput: valores de las tres repeticiones

| Perfil | p95 antes ms | p95 después ms |
|---|---|---|
| Mezcla libre 400/s |54.94 /20.01 /8.05 |9.30 /7.09 /5.42 |
| Mezcla libre 800/s |202.96 /173.57 /14.74 |25.05 /31.69 /6.76 |
| Guest 100/s |10.49 /13.31 /9.54 |14.20 /15.05 /10.09 |
| Suscrito 100/s |12.93 /12.17 /13.81 |13.71 /17.97 /7.75 |

Los p95 se mantienen por repetición; no se promedian para inventar un percentil global. Hay variación y los controles no demuestran una mejora universal. Todas las peticiones emitidas recibieron 200, pero no toda la oferta fue lanzada.

A 400/s la entrega útil se solapa (~396–397/s). A 800/s:antes 705.78/709.89/728.35 útiles/s;después 724.96/720.62/725.32. El tercer baseline supera el tercer candidate. **No se aumenta el techo de capacidad ni se promete un porcentaje fijo de RPS.** El generador sigue dejando oferta sin emitir, especialmente a 800/s.

Guest: 3000/3000 útiles en cada variante. Suscritos: 2999/3000 antes y 2998/3000 después, por llegadas no emitidas, no por errores HTTP. La consulta adicional está documentada y debe reevaluarse si esos perfiles dominan tráfico real.

### Integridad idéntica

Ambas variantes terminaron con 200 cuentas, 180 grants, balance de minutos=journal 54000000 ms, saldo monetario=journal 199999999991 nanoUSD, 1 usage=1 settled de preflight y 0 unresolved. Mismos minutos/saldos finales; ningún cambio de configuración ni concesión duplicada. Stacks, volúmenes y redes del benchmark eliminados.

## Límites y evidencias

Evidencia ignorada en `evidence/`: RED/GREEN, focused, suite, scripts/override de comparación y log/rawstats. Scripts ad hoc no son nuevos comandos públicos mantenidos. Perfil/bench local no equivale a producción, voz, TLS, datos históricos grandes ni soak. No hay cambio de límites o despliegue y no se ha hecho push/PR.

**Risk: items 1, 3, 4 — crédito persistido,helper compartido y concurrencia de primeros intentos.** Pruebas/benchmark/revisión acreditan el alcance local descrito, no una aprobación de despliegue.
