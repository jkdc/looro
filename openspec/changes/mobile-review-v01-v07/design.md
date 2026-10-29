# Diseño de las correcciones V01–V07

## Decisión

Aplicar correcciones en los límites existentes, sin rediseñar sincronización ni almacenamiento. La solicitud de implementar el informe adopta su solución conservadora: preservar datos ambiguos, cercar identidades y retener audio válido.

## Alternativas y compromisos

- Adoptado: comprobar procedencia antes de backfill; cuarentena sin mutaciones si no está demostrada. Evita pérdida a cambio de exigir recuperación explícita de estados ambiguos.
- Rechazado: atribuir primero y usar ownership como permiso de limpieza. Es el defecto V01, no una alternativa segura.
- Adoptado: cercos en los puntos de publicación/escritura, además de cancelación. Cancelar solamente no impide callbacks ya preparados.
- Rechazado: desactivar audio durante la apertura o quitar protección anti-eco. El prefijo válido debe sobrevivir sin admitir eco nuevo.

## Unidades independientes

1. Stores de migración Android/iOS y pruebas (V01/V02): preflight sobre el estado sin normalizar; mantener transacción y claims posteriores.
2. Storage iOS y pruebas (V03): identidad solicitada frente a carga lista, invalidación temprana, gate de mutaciones y retry si la carga falló.
3. ViewModel/UI Android y pruebas (V04/V05/V06): usar identidad ya resuelta, dimensión idioma del caché y contexto capturado de pronunciación.
4. Transporte de voz y pruebas (V07): diferir drenaje del prefijo mientras cualquier locución impida procesarlo; mantener epoch, límite y Finalizar.

## Verificación y seguridad

Fixtures con BD real y estado pre-owner; barreras deterministas para STT/TTS. iOS: añadir pruebas de fallo/carga y revisar todos los writers que dependan de identidad, con ejecución pendiente explícita. No se necesitan migraciones SQL ni dependencias nuevas. Las unidades escriben superficies disjuntas; Gradle se serializa con flock. El padre conserva git y documentación.
