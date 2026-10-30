# Especificación: capacidad acotada de la API

## S1 — Instrumento fiable
El driver SHALL corregir URLs anónimas, rotación de NETWORKS e índice de lanzamiento; SHALL distinguir llegada, completado útil y drenaje, errores y latencias; SHALL acotar concurrencia y tiempo y no iniciar tráfico al importarse.

## S2 — Aislamiento
El smoke/baseline SHALL usar stack y datos efímeros propios, credenciales sintéticas y proveedor falso sin Internet; SHALL rechazar destinos externos, redirecciones y precondiciones inválidas sin emitir carga. SHALL NOT reutilizar tokens o datos persistentes existentes.

## S3 — Observabilidad
OTEL SHALL exponer señales de proceso y proxy con cardinalidad cerrada y sin PII, conservar métricas existentes y liberar sus recursos al apagarse. Réplicas SHALL tener identidades de proceso distinguibles. No se afirma instrumentación completa de adquisición pg en esta fase.

## S4 — Admisión y transporte
El proxy SHALL preservar cuota por cuenta y no-streaming, aplicar límite global por proceso sin cola y acotar deadline/bytes. SHALL emitir proxy_busy/503 con Retry-After: 1, provider_timeout/504 y conservar provider_error/502 para fallos del proveedor, documentando adiciones. Toda salida SHALL limpiar cupos, timers y listeners.

## S5 — Corrección financiera
El proxy SHALL conservar liquidación histórica en éxitos y registrar intentos durables antes del envío. Usage conocido SHALL liquidarse una vez aunque se desconecte el cliente. Un desenlace incierto SHALL quedar auditable sin cargo inventado ni reenvío automático; crash/SQL fallido SHALL NOT presentarse como éxito. Las pruebas SHALL verificar persistencia además de HTTP.

## S6 — Evidencia y alcance
La entrega SHALL incluir RED/GREEN, type-check, pruebas PostgreSQL aisladas y baseline local reproducible, o registrar explícitamente comprobaciones pendientes. SHALL NOT afirmar capacidad de producción ni implementar optimizaciones distribuidas condicionadas sin evidencia adicional.
