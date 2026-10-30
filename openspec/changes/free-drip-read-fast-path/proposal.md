# Evitar transacciones de drip sin efecto

El usuario pide continuar tras presentar el atajo de concesiones ya realizadas en `informe-diagnostico-cpu-sql-api-2026-10-30.md`, secciones 4–5. Se aplica ese cambio acotado, manteniendo ejecución directa en la rama activa.

Objetivo: reducir trabajo de lecturas repetidas sin cambiar minutos, cadencia, elegibilidad, errores de configuración ni contratos HTTP. No introducir caché, índices, migraciones, cuotas nuevas ni workers. Diseño y criterios ya expuestos en el diagnóstico; este change los registra para ejecutar y verificar.
