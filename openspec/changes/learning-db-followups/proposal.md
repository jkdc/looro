# Proposal: learning-db-followups

Follow-ups aprobados por Joaquín (2026-10-05) tras cerrar learning-db:

1. **Drenaje durable del usage_outbox** (Android + iOS): el flush de
   telemetría pasa a drenar TAMBIÉN las filas pendientes de la tabla
   `usage_outbox` (subir + marcar dispatchadas); `archive_health` pasa a
   escribir su fila durable (la que sobrevive cierres) y el flush la sube.
   Server: sin cambios.
2. **Caps: decisión de producto** — se quedan GLOBALES (2000 expresiones
   para todos los idiomas juntos, fiel al motor); registro de la decisión.
   Principio reafirmado: ninguna lectura de UI devuelve varios idiomas —
   siempre el idioma seleccionado (ya se cumple; se audita).
3. **Notebook: borrado multi-idioma con diálogo** (Android + iOS): acción
   "Remove languages…" que abre un diálogo con checkbox POR idioma que tenga
   expresiones, junto a su consumo (nº expresiones + KB aprox), botón de
   confirmación; solo seleccionables los idiomas con datos. El remove por
   fila del idioma actual se conserva. DAO nuevo:
   deleteExpressionsForLanguage (transacción; legacy NULL conservado).

Out of scope: retiro del jsonMode, caps por idioma (descartado), consumo
exacto en disco (se muestra aproximación por longitudes UTF-8).
