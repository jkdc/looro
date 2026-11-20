# Diseño: reparación acotada y diagnóstico por fase

## Decisión

Conservar los candidatos válidos, descartar los defectuosos y pedir solo lo que falta. Cada sección de inglés puede solicitar como máximo una reparación determinista. Pedir más candidatos en cada llamada es más sencillo, pero no corrige la pérdida de trabajo válido ni permite distinguir qué falló. Volver a generar después del juez se deja fuera: aumentaría llamadas y obligaría a preservar evidencia por ítem y reconsiderar los límites de ejecución.

## Flujo

- Analizar cada respuesta y validar ítems planos individualmente; un pasaje y sus preguntas constituyen una unidad indivisible. Validar una unidad fallida no reserva sus claves de deduplicación. Conservar de forma estable las unidades válidas, incluyendo sus etiquetas autorizadas.
- Distinguir descartes locales recuperables de incumplimientos del banco completo. Calcular déficit de cantidad, de tipo de ítem y de cobertura por competencia a partir del blueprint congelado. Si el banco satisface los mínimos, no llamar al reparador por descartes sobrantes. Si no, generar reemplazos para los déficits, validar la unión sin duplicados y pasarla por el juez existente. Una respuesta no analizable tras una llamada conocida equivale a ningún candidato aceptado; un resultado de transporte desconocido sigue siendo terminal.
- Generar instrucciones y ejemplos de formato compatibles con el blueprint: número de opciones/respuestas, campos permitidos, preguntas por pasaje y una competencia primaria. No modificar los ejemplos pedagógicos revisados. Subir la versión del prompt inglés para distinguir el cambio; el contrato del juez no cambia. Para los idiomas legados, conservar cantidades, validación y publicación y añadir únicamente los motivos saneados al reintento existente; versionar ese cambio de prompt también.
- Emitir un span raíz por ejecución, con el identificador de seguimiento del trabajo, y eventos por generación, validación, reparación, juez, composición y resultado. Usar fases y motivos cerrados, conteos, latencia, versiones y uso reportado. Normalizar `finish_reason` a un conjunto cerrado, incluidos desconocido y no informado. No registrar prompts, respuestas, credenciales, pasajes ni excepciones crudas; no usar identificadores para etiquetas de métricas. Sin migraciones ni cambio del contrato público.

## Límites

La reparación no modifica exámenes ya guardados, no relaja el juez ni los controles posjuez y no vuelve publicable un resultado que antes requería aprobación. No activar nuevos idiomas, proveedores ni llamadas reales. No atribuir mejoras de calidad pedagógica a una suite determinista.
