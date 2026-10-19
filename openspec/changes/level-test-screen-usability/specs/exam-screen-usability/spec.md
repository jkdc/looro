# Spec: exam-screen-usability

## ADDED Requirements

### Requirement: Entradas de texto visibles
La pantalla de examen DEBE presentar toda entrada de texto (gap/written)
dentro de un contenedor con borde visible y placeholder, y el teclado NO DEBE
ocultar el input enfocado (imePadding).

#### Scenario: escribir una respuesta escrita
- WHEN el examinador toca el campo de texto de una pregunta escrita
- THEN el campo mantiene el foco visible sobre el teclado y acepta texto

### Requirement: Opciones de elección distinguibles
Toda opción de elección DEBE mostrarse con borde y fondo; la seleccionada
DEBE distinguirse (relleno + check).

#### Scenario: seleccionar una opción
- WHEN el examinador toca una opción
- THEN la opción marcada muestra relleno y check, y las demás conservan borde

### Requirement: Entrega con huecos marcados
El botón de entrega DEBE habilitarse con al menos una respuesta; si quedan
preguntas sin responder, DEBE marcarlas en rojo y pedir confirmación
explícita con el contador antes de enviar. Las respuestas en blanco cuentan
como incorrectas.

#### Scenario: entregar con huecos
- WHEN el examinador pulsa entregar con preguntas sin responder
- THEN las preguntas sin respuesta se marcan en rojo con su chip y el botón
  pide confirmación; un segundo toque entrega el examen

### Requirement: Autoguardado silencioso
El examen activo NO DEBE mostrar rótulos de autoguardado correcto; los
fallos de guardado DEBEN seguir siendo visibles con reintento.

#### Scenario: responder una pregunta
- WHEN el examinador responde y el autoguardado tiene éxito
- THEN no aparece rótulo alguno de guardado en el examen activo

### Requirement: Instrucciones por sección
Cada cabecera de sección DEBE ofrecer un botón de información que
despliegue/oculte una explicación corta de qué se espera en esa sección.

#### Scenario: consultar las instrucciones
- WHEN el examinador pulsa el botón de información de una sección
- THEN la explicación se muestra y puede ocultarse de nuevo

### Requirement: Reproductor de escucha
El pasaje de escucha DEBE reproducirse con un control único play/stop y una
barra de progreso con seek (rebobinar/avanzar reiniciando el habla desde el
punto elegido); NO DEBE permitirse solapar reproducciones y el control DEBE
volver a reposo al terminar.

#### Scenario: escuchar y rebuscar
- WHEN el examinador reproduce un pasaje y arrastra la barra
- THEN la reproducción se reinicia desde la fracción elegida y el progreso
  avanza hasta el final, volviendo el control a reposo
