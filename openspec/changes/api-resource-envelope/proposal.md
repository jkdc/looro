# Capacidad con presupuesto compartido y demanda móvil

Diseño aprobado mediante «sí» tras presentar `design.md`. Se autoriza planificar y ejecutar los pendientes 1–4, no desplegar; después analizar Android/iOS. Se conserva la ejecución directa en la rama activa.

Objetivo: medir cuánto trabajo útil soporta el conjunto API/PostgreSQL/auxiliares con 1 vCPU/4 GB o 2 vCPU/8 GB, y un pico experimental de 4 vCPU/12 GB, sin monopolizar permanentemente el servidor compartido Ubuntu/Coolify. Evaluar el último tradeoff, primeras concesiones, resistencia y fallos; Redis solo con evidencia. El panel puede usarse bajo demanda, pero hoy comparte Caddy y API.

La fase produce harness reproducible, resultados observados y guía no aplicada. No certifica el servidor real ni convierte llamadas sintéticas en usuarios reales. Pruebas con PostgreSQL/datos/proveedores sintéticos, recursos locales y sin salida a servicios externos.
