# Fiabilidad del contenido diario

## Requisito: recuperación localizada antes del juez

Si una respuesta conocida trae una pregunta inválida con identidad y clave inequívocas, el worker podrá intentar reparar únicamente ese par una vez. Debe conservar los demás datos byte por byte en su representación canónica. La reparación se valida con el paquete entero; un reemplazo con otro ID/tipo, una clave incompatible o una segunda validación fallida no autoriza publicación ni otra reparación. El juez debe recibir únicamente el paquete final y sus hashes.

## Requisito: trazas y cancelación seguras

Los registros distinguen espera, llamadas al proveedor, validación y resultado listo/publicado. Se correlacionan por trabajo y no contienen material generado, datos de alumnos, credenciales ni errores crudos. Perder el lease o cancelar antes del despacho no puede iniciar una nueva llamada. Una llamada incierta después del despacho no se vuelve a emitir automáticamente. Las operaciones y el uso permanecen diferenciados en el historial y respetan el presupuesto existente.
