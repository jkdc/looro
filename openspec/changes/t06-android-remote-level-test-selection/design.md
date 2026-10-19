# Diseño: selección remota segura de exámenes Android

## Contrato confirmado

El endpoint público `GET /v1/level-tests/hybrid` devuelve una composición semanal con `schemaVersion`, `languageID`, `level`, `version`, `weekId`, `expiresAt`, `thresholds`, `passages`, `readingPassageIds`, `listeningPassageIds`, `items` y metadatos de identidad/origen. Acepta `language`, `level` y `variant`; el servidor selecciona una variante aleatoria si se omite, así que el cliente enviará siempre `variant=1`. La respuesta entrega un examen ya compuesto: Android debe preservar el orden/ítems de `items`, no recomponerlos como si fueran un pool. ETag y `Cache-Control` habilitan revalidación; 304 solo es utilizable con el mismo identity/variant y un body cacheado que vuelva a validar.

## Componentes

- `LevelTestRemoteClient`: OkHttp público sin cookies ni credenciales, redirects ni retries; límite total 5 s; body acotado antes de parsear; cancellation de coroutine cancela el `Call`.
- DTO y adaptador: JSON tolerante a campos futuros, pero validación obligatoria de versión soportada, identidad solicitada, semana/variante, metadatos, tamaño, IDs únicos, secciones/kinds, referencias de pasaje, opciones y respuestas antes de producir un examen local.
- Cache de catálogo: archivos de `cacheDir` nombrados por clave derivada de language/level/variant; escritura atómica y límite de bytes. Cache desechable no comparte filas ni borrado con snapshots de intentos. Persistir solo body validado, ETag y expiración; revalidar con variante fija.
- Repository: fallback a la composición bundled validada ante 404, timeout, error de red o contenido inválido. Cancelación se propaga. Errores ordinarios son tipados y no contienen body/respuestas. Refresh no cambia una selección activa.
- Selección: el screen captura una única selección inmutable al entrar. Una actualización termina en cache y queda disponible para el siguiente intento; nunca reemplaza el examen actual después de responder.

## UTC / expiración

El `weekId` se valida con año-semana ISO UTC y `expiresAt` debe ser una fecha válida que corresponda al límite de la semana. La expiración controla revalidación del catálogo, nunca la validez de un snapshot de intento.

## Pruebas

JVM con MockWebServer y fake clock/cache: respuesta válida, schema/identity/IDs/referencias inválidos, body sobredimensionado, deadline total, cancelación, fallback, 304 con cache válida y corrupta/ausente, variante fija, y resultado remoto tardío sin sustituir selección capturada. Ejecutar tests, lint y build Android dentro del Docker runner documentado.
