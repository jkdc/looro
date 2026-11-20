# Diseño: práctica diaria recuperable y diagnosticable

## Decisión

Reparar solo una pregunta de comprensión oral o práctica y su clave. Volver a generar todo es más sencillo, pero desperdicia el material válido, aumenta coste y puede romper la coherencia. Relajar el validador podría publicar material no verificable; no se considera.

## Flujo

1. Generar el paquete. Si falla un requisito determinista que identifica una pregunta existente con ID, tipo y posición inequívocos y una clave identificable, construir un objetivo interno de reparación. No reparar JSON ilegible, estructura raíz inválida, bloque ausente ni fallos del juez.
2. Solicitar solo el par pregunta/clave con el contexto mínimo de la actividad y las expresiones válidas. Verificar igualdad de ID y tipo con el objetivo, igualdad de ID en la clave y forma exacta de la respuesta. Combinar sobre una copia; no modificar otros ítems, pasaje, metadatos ni evidencias. Volver a validar ambos artefactos completos antes de guardar candidatos, calcular hashes y ejecutar el juez.
3. Registrar la reparación como operación independiente dentro del mismo trabajo, reutilizando la fase persistida `generate` con una clave de despacho nueva y única para reparación. La llamada comparte la reserva de contenido, respeta los mismos límites y lease, y conserva uso propio. No ejecutar más de una reparación; no repetir automáticamente una llamada de resultado desconocido. No reabrir trabajos existentes ni evadir el presupuesto.
4. Correlacionar por UUID del trabajo; distinguir espera en cola, generación, validación, reparación, juez y publicación. Registrar códigos cerrados, duración, motivo de fin normalizado y uso conocido. Eliminar muestras de objetivos, nombres de campo arbitrarios, errores crudos y texto del proveedor en errores de generación. No usar UUID como etiqueta de métricas.
5. Propagar una señal de cancelación al adaptador diario de contenido, abortar cuando el worker pierda el lease y comprobar la señal antes y después del checkpoint previo al envío. Mantener el fencing en PostgreSQL y la política terminal de resultado desconocido. No cambiar la revisión de intentos de alumnos más allá de la comprobación de señal y el saneado de errores en el transporte compartido.

## Fuera de alcance

Migraciones, nuevo estado de publicación, reintento manual de trabajos terminales, reparación posterior al juez, cambios en clientes, proveedores reales y despliegue. Las trazas podrán explicar cuánto se espera en cola, no garantizan reducir esa espera.
