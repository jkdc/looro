# Diseño pedagógico y presupuesto de tiempo

## Principio

**Mismo objetivo y contenido; ayudas opcionales para acceder a ellos.** No se
calcula el nivel del alumno para elegir material ni se inyectan sus errores
históricos en el briefing diario. La dificultad intermedia/alta no debe depender
de instrucciones confusas, vocabulario raro o preguntas-trampa.

Referencia propuesta: tareas comunicativas B1 con extensión B2. No equivale a
una certificación del paquete ni garantiza que un A1 pueda resolverlo sin ayuda.
El andamiaje se apoya en explicación, ejemplos y segmentación, coherentes con
las estrategias del [volumen complementario del MCER](https://rm.coe.int/09000016809ea0d4).
Su eficacia y duración en esta app requieren un piloto específico.

## Un único paquete, no varios recorridos

Todos reciben las mismas preguntas, opciones, briefing y ejercicios, en el
mismo orden. Las ayudas no sustituyen preguntas ni reducen objetivos. Están
preparadas en el paquete y se abren por decisión del usuario. No se envía al
generador el perfil, historial, nombre ni progreso de los alumnos.

El tutor responde a lo que dice el usuario, puede repetir una pregunta y ofrecer
una pista solicitada. No crea una lección distinta, no importa vocabulario de
intentos anteriores y no altera las reglas comunes. Este límite debe probarse
porque las políticas actuales de conversación pueden usar historial personal.

## Forma y tiempos del paquete inglés v1

| Etapa | Contenido fijo | Presupuesto activo |
|---|---|---:|
| Preparación | Topic, objetivo y 5 expresiones con significado y ejemplo | 120 s |
| Listening | Audio de 60–90 s, hasta 2 reproducciones y 3 preguntas de elección única | 240 s |
| Speaking | Conversación natural y dinámica sobre el topic, con apertura y cierre disponibles | ≈360 s |
| Práctica | 4 ejercicios comunes: 2 elección única y 2 huecos de respuesta acotada | 180 s |
| Revisión y Words | Fortalezas, hasta 3 prioridades y selección/aporte manual | 120 s |
| **Total activo previsto** | Incluye consultas de ayudas y transiciones | **1.020 s (17 min)** |

Los 17 minutos son una estimación de diseño, no un plazo. No hay cuenta atrás,
corte por tiempo ni límite de duración. El piloto mide duración real para
mejorar la estimación sin interrumpir sesiones largas.

### Duración orientativa y guardado continuo

- La aplicación puede mostrar «aprox. 15–20 min» antes de empezar, nunca una
  cuenta atrás ni un aviso que presione a cerrar.
- Guardar incrementalmente cada respuesta/evento y mensaje enviado o recibido.
  Persistir en local antes de transmitir; en API confirmar por evento y reintentar
  con el mismo ID si el resultado de red es desconocido.
- Al volver tras un cierre inesperado, abrir el mismo intento y topic desde la
  última respuesta confirmada. No expira por fecha ni duración; no abrir otro
  intento para recuperar el primero.
- Conservar intentos y revisiones para consultarlos después según el ciclo de
  vida de la cuenta. Separar historial legible de texto transitorio de transporte.
- Salir mientras la revisión está pendiente es seguro; al volver se carga el
  resultado, sin solicitar otra evaluación ni requerir una sesión viva.
- Límites técnicos del proveedor pueden renovar la conexión de voz, nunca
  finalizar el modo diario. Guardar el estado y continuar en el mismo intento.
  Restricciones reales de saldo/API se muestran sin fingir un límite de tiempo.

## Ayudas comunes

| Etapa | Ayuda | Señal registrada |
|---|---|---|
| Preparación | Significado breve y un ejemplo por expresión | Apertura de ejemplo |
| Listening | Repetición y transcripción completa a demanda | Reproducciones y transcripción vista antes de responder |
| Speaking | Pista por meta, inicio de frase y respuesta de ejemplo | Tipo y meta de ayuda solicitada |
| Práctica | Pista común antes de enviar; explicación después | Pista vista antes de enviar |

Las ayudas textuales tienen versión `en` y `es` común para el lanzamiento;
otras preferencias de interfaz usan `en` con aviso de idioma de ayuda. Esto no
activa cursos en español ni mezcla el idioma de aprendizaje. El idioma de
explicación final se congela al iniciar el intento: `en` o `es`.

Las mismas ayudas deben ser accesibles con lector de pantalla y sin depender
de color. Permiso de micrófono denegado conserva el resto del intento, explica
la limitación y permite terminarlo como parcial; texto no se etiqueta speaking.

## Listening y práctica: corrección

Elección única compara IDs de opción. Los huecos contienen `acceptedAnswers`
explícitos por perfil; no se piden ensayos ni se califican por coincidencia
difusa. Para inglés: NFC, trim, espacios consecutivos a uno, minúsculas según
regla del perfil; no eliminar apóstrofos, negaciones ni palabras. Cada variante
válida debe estar en la clave y tener una prueba. Respuesta vacía = omitida.

El servidor corrige con claves privadas congeladas; las apps pueden mostrar
estado enviado, no inventar una nota. No se muestran claves antes de enviar la
etapa. Las explicaciones hacen referencia al contenido exacto del día.

## Speaking conversacional y revisión final

Evaluar cumplimiento comunicativo, inteligibilidad **textual**, gramática y uso
de expresiones observables en fragmentos del alumno. No afirmar evaluación
acústica de pronunciación, acento o fluidez a partir de una transcripción.

Cada observación incluye fragmento exacto, IDs fuente, corrección, explicación
y categoría. Se descarta una cita inexistente o procedente solo del tutor. Una
transcripción marcada dudosa no fundamenta una corrección firme.

La revisión devuelve por sección: `sufficient`, `limited` o `not_observed`;
fortalezas hasta 3; prioridades hasta 3; errores hasta 6; candidatos de Words
hasta 8. No hay nota global de aprobado/suspenso ni avance automático CEFR.
Completar con ayuda cuenta como práctica, no como prueba de dominio autónomo.

Con menos de 2 fragmentos no vacíos del alumno, speaking se marca
`not_observed`, sin inventar fortalezas ni errores. Puede haber revisión del
listening y práctica y candidatos justificados por respuestas falladas.

El alumno puede marcar una corrección como no útil y eliminar palabras de
Words. No se convierte la opinión del LLM en una verdad irreversible.

## Perfil lingüístico extensible

`DailyLanguageProfile` contiene ID de idioma, versión, rúbrica, constructores de
prompts, normalización de respuestas, segmentación/validación de texto, ayudas,
voces TTS permitidas y fixtures lingüísticos. El motor solo conoce capacidades,
IDs, límites de bytes y duraciones; no contiene expresiones regulares inglesas.

Inicialmente solo existe una implementación habilitada: `en`. Activar otro
idioma exige perfil, referencias propias, revisión lingüística independiente,
fixtures válidas/inválidas, audio y pruebas completas de generación/juez/
publicación/móvil. Traducir un prompt no equivale a soporte verificado. Los
límites por palabras inglesas, si se usan en autoría, viven en el perfil.

## Piloto y criterio de salida

Propuesta de piloto autorizado separado: al menos 5 participantes de nivel bajo
y 5 intermedio/alto, sin usar sus grabaciones fuera del consentimiento acordado.
Medir inicio→resultado disponible, duración real, abandonos, ayudas, cobertura
de objetivos y utilidad de correcciones. Usar 15–20 min como hipótesis de
experiencia, nunca como criterio de interrupción o rechazo; informar mediana y
percentiles y escuchar a alumnos que quieran continuar más tiempo. Si falla, reducir volumen o mejorar ayudas del **paquete común**;
no activar personalización silenciosamente. Estos umbrales son de producto,
no una validación estadística de eficacia o equivalencia de niveles.
