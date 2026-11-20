# Fiabilidad de exámenes generados

## Requisito: conservar y reparar candidatos

- Si una sección inglesa recibe ocho pasajes, uno no es válido y los siete restantes satisfacen tamaño, cobertura, formato y diversidad requeridos para el banco, debe continuar sin reparación determinista. El pasaje rechazado no puede llegar al juez ni al examen.
- Si recibe cinco pasajes válidos con mínimo seis y las otras cuotas se satisfacen, debe pedir un pasaje, conservar los cinco y validar la unión. Si el reemplazo es inválido o duplicado, debe fallar sin segunda reparación ni publicación.
- Un déficit de tipo o de competencia debe conservarse en el diagnóstico y en la petición de reparación, incluso si hay candidatos suficientes en número. Un pasaje rechazado no debe contaminar la deduplicación de los demás.
- Español y los demás idiomas legados deben recibir el motivo del rechazo en el segundo intento sin alterar su política. Una cancelación o un resultado de transporte desconocido no autoriza más llamadas.

## Requisito: prompts verificables y trazas diagnósticas

- Los ejemplos estructurales y las instrucciones de salida deben coincidir con el validador y el blueprint. Versionar los prompts modificados.
- Por ejecución se debe poder distinguir generación, reparación, juez, composición y publicación. Un HTTP 202 no se cuenta como resultado; un borrador y un aprobado son resultados distintos. Los eventos contienen conteos, latencia y uso reportado; la ausencia de datos no se trata como cero. No admitir texto del proveedor, contenido de examen ni secretos.
- La entrega debe incluir replay determinista y una comparación offline reproducible. No presentar la calidad de una respuesta real como verificada sin un piloto autorizado.
