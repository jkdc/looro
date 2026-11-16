# Práctica diaria común — guía de implementación

**Estado: diseño aprobado con implementación en curso.** Fecha: 2026-11-08.
El usuario aprobó TTS local para listening y el uso del proveedor LLM ya configurado
para exámenes en generación/revisión diaria. Esto autoriza implementar los
consumidores, pero no llamadas reales a proveedores, cambios de precios, activación
de Daily Practice ni despliegue.

## Qué se va a construir

Un paquete diario común, inicialmente en inglés, que conecta un topic con
listening, speaking preparado, práctica sencilla y revisión individual. Las
palabras y expresiones resultantes se incorporan a **Words**. El recorrido
apunta a 15–20 minutos de duración habitual, sin temporizador ni corte. Incluye
reto intermedio/alto y ayudas comunes.

La v1 admite **un intento por alumno, idioma y fecha** (límite configurable),
guarda el estado para poder reanudarlo y conserva el resultado para consultarlo
después. El speaking es conversación natural sobre el topic, no una entrevista
de preguntas prefijadas.

No hay selección del temario por alumno, historial, errores ni nivel. La
conversación responde naturalmente al alumno; eso no cambia el paquete diario.
El paquete de listening conserva solo el texto y el locale de voz; cada app lo
reproduce con su TTS local existente. La falta de voz para ese locale se muestra
al alumno, sin sustitución silenciosa por otro idioma.

## Ruta de lectura

| Documento | Resuelve |
|---|---|
| [proposal.md](proposal.md) | Alcance aceptado, alternativas y límites de autorización |
| [design.md](design.md) | Componentes, estados, speaking, generación y publicación |
| [pedagogy.md](pedagogy.md) | Dificultad, ayudas, tiempos y rúbrica |
| [contracts.md](contracts.md) | DTO, rutas, errores, idempotencia y administración |
| [data-and-sync.md](data-and-sync.md) | Persistencia, concurrencia, recuperación y Words |
| [specs/daily-practice/spec.md](specs/daily-practice/spec.md) | Requisitos verificables y escenarios |
| [tasks.md](tasks.md) | Secuencia de implementación y pruebas por entrega |
| [verify-report.md](verify-report.md) | Comprobaciones ejecutadas y pendientes |

Los contratos y la matriz normativa prevalecen sobre ejemplos abreviados. Los
documentos describen interfaces **nuevas propuestas**, no endpoints existentes.
Si cambia una decisión, actualizar contrato, requisito y tarea vinculados juntos.

## Decisiones de diseño que requieren revisión del propietario

El usuario confirmó el objetivo, la sesión común, inglés inicial, extensibilidad
de idiomas, topic, speaking preparado, integración con Words y ayudas comunes.
Para hacer el diseño ejecutable se proponen además estos valores por defecto:

1. **Reto B1–B2 orientativo**, sin certificar un nivel por completar la sesión.
2. **Día editorial UTC**, idéntico para todos, con fecha explícita en móvil.
3. **Primera versión con speaking hospedado y bearer válido**, incluyendo las
   cuentas invitadas que ya admita la política de voz. No exige registro nuevo
   a un invitado autorizado. BYOK y motores locales mantienen sus modos libres,
   pero no ejecutan este recorrido en la primera entrega.
4. **Publicación automática solo tras validación y juez independiente**, con
   interruptor de publicación manual y retirada desde administración.
5. **Revisión final financiada por un presupuesto operativo acotado**, sin nuevo
   cobro automático al usuario. El usuario aprobó reutilizar el mismo proveedor LLM
   que ya se configura para los exámenes; no se crea otra credencial ni un proveedor
   separado. La disponibilidad de ese proveedor no habilita por sí sola Daily Practice:
   se mantienen sus flags y presupuestos propios (cero y apagados por defecto). Si el
   presupuesto no permite reservar la revisión, no se inicia un nuevo intento; nunca
   se promete revisión sin cobertura. Speaking conserva permisos y consumo hospedados
   existentes.

El usuario ha aprobado la financiación operativa limitada de la revisión y el
reuso del proveedor LLM configurado para exámenes. Siguen siendo propuestas las
demás decisiones no confirmadas, en particular la política de acceso y los
defaults de publicación. No se activará la función ni se llamará a proveedores
reales sin autorización separada.

## Qué significa «terminado»

- Contenido común publicado y reproducible; errores de generación no se sirven.
- Android e iOS completan y recuperan el mismo recorrido.
- Revisión basada en respuestas reales, con incertidumbre explícita.
- Words recibe aportaciones sin duplicados, sin perder progreso ni resucitar
  expresiones eliminadas al releer un resultado.
- API, PostgreSQL aislado y E2E de administración tienen pruebas de los flujos
  nuevos; los controles existentes conservan su comportamiento.
- Piloto autorizado demuestra el tiempo y la accesibilidad para nivel bajo.

La suite verde no sustituye al piloto ni a las pruebas específicas del requisito.
No se declara eficacia pedagógica o rentabilidad probada por tener este diseño.
