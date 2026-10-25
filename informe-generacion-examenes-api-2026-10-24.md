# Generación de exámenes desde la API: calidad, suficiencia y mejoras justificadas

**Fecha:** 24 de octubre de 2026. **Código analizado:** `4768ff7`, rama activa `feat/admin-panel-hardening`.
**Alcance:** análisis y propuesta de implementación; no se ha cambiado el producto, la configuración ni el contenido.

## 1. Conclusión ejecutiva

**Sí hay mejoras necesarias y verificables. La principal no es pedir simplemente más preguntas al modelo.** El sistema actual está diseñado como una comprobación semanal breve: 25 respuestas puntuadas, cuatro textos de comprensión y tres ejercicios de producción cerrada. Es aprovechable para práctica y orientación, pero no aporta por sí mismo evidencia suficiente para afirmar preparación integral de un nivel.

Hay dos problemas diferentes:

1. **Desajustes técnicos corregibles:** el generador ignora el tamaño de banco y las restricciones por nivel declaradas; las variantes pueden tener exactamente las mismas preguntas; la comprobación de idioma tiene defectos reproducibles; el reintento dirigido no funciona ante varios errores de contenido; y la contabilidad de tokens por generación permanece a cero.
2. **Límites de lo que se evalúa:** la escritura es una respuesta corta comparada con soluciones enumeradas, no una redacción; no entra expresión oral en la nota; no hay composición por cobertura de capacidades ni calibración empírica que vincule el aprobado con preparación global.

**Recomendación:** corregir primero la cadena existente, conservar el examen breve y, después, añadir un diagnóstico de preparación con tareas productivas y más evidencia independiente. No sustituir lo que funciona ni convertir todas las sesiones semanales en un simulacro largo.

### Prioridades

| Prioridad | Actuación | Justificación |
|---|---|---|
| P0 | Conectar modelo de examen, prompts y validación | Hoy hay requisitos escritos que el flujo real no aplica. |
| P0 | Reparar validación de idioma, chino y errores estructurales | Hay fallos reproducidos con entradas controladas. |
| P0 | Hacer real el reintento dirigido y probar el rechazo efectivo | Se pierde una generación completa por errores reparables. |
| P1 | Generar un banco mayor que una forma y medir diversidad | Tres identificadores de variante no implican tres conjuntos de preguntas. |
| P1 | Mejorar rúbrica y resolver explícitamente las dudas del juez | `strict` actualmente admite `flagged`; no equivale a contenido sin dudas. |
| P1 | Controlar cobertura e interpretar honestamente el resultado | Más preguntas de la misma regla no demuestran más capacidades. |
| P1, segunda etapa | Diagnóstico con escritura abierta y expresión oral | Necesario si se pretende valorar preparación integral, no solo práctica breve. |
| P2 | Corregir uso de tokens y completar fallback B2/C1 | Permite presupuestar la mejora y evita huecos de disponibilidad. |

Las prioridades expresan la recomendación de este informe, no decisiones ya aprobadas para implementar.

## 2. Método y límites del análisis

Se revisaron el flujo de generación, prompts activos, modelo JSON, validador, composición, juez, publicación, pruebas, bancos bundled y motores de corrección de Android/iOS. Tres revisiones independientes cubrieron generación/validación, suficiencia del contenido y skill/prompts; después se contrastaron los hallazgos y se reprodujeron casos locales.

Se utilizaron:

- Inspección de código y especificaciones OpenSpec, distinguiendo intención histórica de comportamiento actual.
- Conteos directos de los JSON y del cosechador, sin regenerar contenido.
- Pruebas locales con transporte LLM y SQL simulados, sin llamadas a proveedores.
- Comprobación de tipos y selección de pruebas existentes, detalladas en §13.
- Fuentes primarias sobre evaluación lingüística, para evitar presentar preferencias de diseño como requisitos oficiales.

CodeGraph tenía directorio de índice, pero no estaban disponibles sus herramientas MCP ni su ejecutable en este entorno; se empleó inspección de fuentes. No se inicializó ni reconstruyó el índice.

**No se consultaron la BD de producción, claves, configuraciones efectivas ni exámenes publicados. No se llamó a ningún modelo real.** Por tanto:

- Un fallo con un stub demuestra que una garantía falta; no demuestra que todos los exámenes reales tengan ese fallo.
- Los tamaños del generador indican lo solicitado por código, no un inventario de producción.
- No se puede establecer qué proveedor, modelo, modo de verificación o publicación están activos actualmente.
- No se ha medido concordancia con docentes, tiempo real del alumnado, dificultad empírica ni capacidad predictiva del aprobado.

## 3. Qué se ejecuta realmente

### 3.1. La «skill» de la API no es el skill editorial

Conviene separar tres piezas:

| Pieza | Función real | ¿La usa la API durante la generación? |
|---|---|---|
| `.agents/skills/looro-content/SKILL.md` | Guía para agentes que editan contenido canónico y ejecutan exporters | No se carga en este flujo. |
| `shared/leveltests/exam-models.json` | Modelo pedagógico declarado: nivel, ancla, cantidades, rangos, formatos y prohibiciones | Se consulta, pero solo parte de sus campos gobierna el flujo. |
| `sectionPrompt()` y `judgeSection()` | Instrucciones que recibe el modelo generador y el juez | Sí; son el contrato efectivo. |

No existe una carga de `SKILL.md` de producción en el recorrido inspeccionado. Mejorar únicamente el skill editorial **no cambiaría las instrucciones que recibe el modelo**.

La corrección útil es alinear contrato tipado, mensajes y validación. No hace falta introducir un framework de skills, agentes adicionales o RAG para conseguirlo.

**Evidencia:** `services/api/src/level-tests-llm.ts:31–52,108–161,175–181,240–249,273–287`.

### 3.2. Recorrido actual

```text
Petición del operador / trabajo automático
  → selección del modelo de examen por nivel
  → resolución de proveedor y modelo: BD activa → entorno
  → cuatro generaciones por sección, secuenciales
  → combinación del banco y validación determinista
  → juez por sección, si verifyMode no es off
  → eliminación de reject y nueva comprobación de mínimos
  → composición de variantes y snapshots con identidad/hash
  → draft o approved según publishMode
  → endpoint público sirve exclusivamente contenido aprobado
```

Lectura y escucha sí aportan al juez el texto fuente. No se le pregunta sobre una comprensión sin mostrarle el pasaje.

Las cuatro secciones están separadas, pero la validación determinista completa llega **después de generarlas todas**, no inmediatamente después de cada una.

**Evidencia:** `services/api/src/level-tests-llm.ts:292–398,575–594`; `services/api/src/llm-config.ts:75–93`.

### 3.3. Las «tres puertas» son configurables

Los valores por defecto en código son publicación `review`, verificación `flag` y tres variantes. No equivalen a configuración efectiva del despliegue.

- Con `verifyMode=off` no se ejecuta el juez.
- Con `verifyMode=flag`, una indisponibilidad del juez puede quedar anotada como duda.
- Con `verifyMode=strict`, se exige respuesta válida y completa del juez, pero se admiten ítems `flagged`.
- Con `publishMode=auto`, no hay aprobación humana previa.
- Con `publishMode=review`, el examen queda como borrador hasta su aprobación.

Por ello, **no es correcto describir todos los exámenes como contenido que obligatoriamente superó tres revisiones independientes**.

El panel de revisión humana ya existe. Las especificaciones iniciales que lo dejaban como trabajo futuro no representan el estado actual.

**Evidencia:** `services/api/src/level-test-settings.ts:7–19`; `services/api/src/level-tests-llm.ts:340–383`; `services/api/src/level-tests-publication.ts:51–55`; `services/api/docs/admin-panel.md:95–100`.

## 4. Cantidad real de contenido

### 4.1. Lo que recibe una persona en una forma

Los cinco modelos A1–C1 declaran la misma estructura:

| Sección | Respuestas puntuadas | Estímulos independientes |
|---|---:|---|
| Reconocimiento | 12 | 12 ejercicios breves |
| Lectura | 6 | 2 textos × 3 preguntas |
| Escucha | 4 | 2 textos × 2 preguntas |
| Escritura | 3 | 3 respuestas cortas con soluciones enumeradas |
| Expresión oral | 0 | Ninguna actuación oral incorporada a esta calificación |
| **Total** | **25** | **15 ejercicios sueltos y 4 bloques de comprensión** |

No hay seis lecturas ni cuatro grabaciones distintas: hay dos estímulos de cada modalidad. Varias respuestas comparten texto y contexto, por lo que no deben interpretarse como observaciones completamente independientes.

El motor documenta una duración aproximada de 10–15 minutos; es intención de diseño, no una duración observada en este análisis.

**Evidencia:** `shared/leveltests/exam-models.json`; `services/api/src/level-tests-llm.ts:68–72`; `apps/android/app/src/main/java/com/jkdc/app/core/LevelTests.kt:42–50`.

### 4.2. Banco solicitado frente a banco declarado

| Elemento | `poolTarget` declarado | Solicitud real | Consumo de una forma |
|---|---:|---:|---:|
| Reconocimiento | 24 | **12** | 12 |
| Escritura cerrada | 6 | **3** | 3 |
| Textos de lectura | 4 | **2** | 2 |
| Textos de escucha | 4 | **2** | 2 |
| Respuestas puntuables resultantes | 50 | **25** | 25 |

La última fila supone tres preguntas por lectura y dos por escucha, como en el modelo actual.

`generateSection()` recibe el modelo, pero toma los tamaños de `SECTION_SPECS`. No utiliza `poolTarget`.

**Consecuencia:** si el proveedor devuelve exactamente lo pedido, las tres variantes utilizan las mismas 25 preguntas y los mismos cuatro textos. El hash puede cambiar el orden, pero no crear contenido adicional.

Un probe con transporte simulado y tres variantes confirmó un único conjunto de IDs. Las 75 posiciones entre las tres formas no representaban 75 preguntas diferentes.

El banco doble declarado permitiría selección con diversidad; **no garantiza por sí solo 50 % exacto de solapamiento**. Tres formas totalmente disjuntas exigirían, como mínimo, 36 reconocimientos, nueve escrituras, seis lecturas y seis escuchas, además de equivalencia de cobertura y dificultad. No recomiendo triplicar por defecto: primero hay que definir qué diversidad se necesita.

**Evidencia:** `services/api/src/level-tests-llm.ts:68–72,175–181,436–474`; `openspec/changes/level-tests-llm/proposal.md:26–40`.

### 4.3. Diferenciación de longitud por nivel

Rangos por pasaje; «palabras» significa aquí el conteo por espacios existente, no una medida lingüística universal.

| Nivel | Lectura declarada | Escucha declarada | Lectura pedida realmente | Escucha pedida realmente |
|---|---:|---:|---:|---:|
| A1 | 50–90 | 35–70 | 60–120 | 40–90 |
| A2 | 60–100 | 40–80 | 60–120 | 40–90 |
| B1 | 70–120 | 50–90 | 60–120 | 40–90 |
| B2 | 80–140 | 50–100 | 60–120 | 40–90 |
| C1 | 100–180 | 60–120 | 60–120 | 40–90 |

Además, el validador admite lectura de **30–220** y escucha de **20–200**, sin recibir el nivel ni sus rangos.

Sí hay focos distintos por nivel en `SKILLS_BY_LEVEL`; no sería correcto afirmar que A1 y C1 reciben exactamente las mismas instrucciones. El problema es que conviven tres fuentes de reglas incompatibles: JSON, prompt y validador.

Para C1, la solicitud actual suma solo 120–240 palabras de lectura y 80–180 palabras de guion de escucha. Eso limita la variedad que puede observarse en una única forma. No demuestra automáticamente que cada pregunta sea fácil: la longitud no sustituye al análisis de tarea y demanda cognitiva.

**Evidencia:** `shared/leveltests/exam-models.json`; `services/api/src/level-tests-llm.ts:75–158`; `services/api/src/level-tests-validate.ts:50–56,108–123`.

### 4.4. Banco bundled disponible

El fallback no aporta preguntas nuevas a una forma generada. `/hybrid` elige un examen generado completo si existe y, en su defecto, uno determinista completo.

Inventario medido en los JSON actuales:

| Idioma/nivel | Reconocimiento | Escritura cerrada | Preguntas lectura | Preguntas escucha | Textos únicos compartidos por ambos roles |
|---|---:|---:|---:|---:|---:|
| EN A1 | 129 | 39 | 45 | 30 | 15 |
| EN A2 | 57 | 18 | 36 | 24 | 12 |
| EN B1 | 107 | 35 | 27 | 18 | 9 |
| ES A1 | 131 | 40 | 48 | 32 | 16 |
| ES A2 | 64 | 18 | 36 | 24 | 12 |
| ES B1 | 53 | 14 | 21 | 14 | 7 |
| **Total** | **541** | **164** | **213** | **142** | **71** |

Son 1.060 entradas puntuables almacenadas, no 1.060 tareas independientes ni preguntas nuevas por semana. Los textos tienen rol de lectura y de escucha; dentro de una forma se evita usar el mismo en ambos.

| Idioma/nivel | Longitud de los textos: mínimo / media / máximo |
|---|---|
| EN A1 | 61 / 92,6 / 114 palabras |
| EN A2 | 67 / 87,5 / 119 |
| EN B1 | 103 / 123,4 / 159 |
| ES A1 | 60 / 76,7 / 100 |
| ES A2 | 69 / 93,4 / 107 |
| ES B1 | 88 / 101,6 / 112 |

Los rangos de generación no deben aplicarse retroactivamente al banco cosechado como si fueran la misma política.

Hay contenido fuente B2/C1, pero no alcanza todos los mínimos de cosecha —36 reconocimientos, nueve escrituras y seis textos—:

| Idioma/nivel | Fuente: reconocimiento / escritura / textos | Déficit técnico: reconocimiento / escritura / textos |
|---|---|---|
| EN B2 | 30 / 8 / 8 | 6 / 1 / 0 |
| EN C1 | 21 / 5 / 3 | 15 / 4 / 3 |
| ES B2 | 24 / 5 / 7 | 12 / 4 / 0 |
| ES C1 | 26 / 5 / 5 | 10 / 4 / 1 |

Completar esos déficits habilitaría el fallback técnicamente; no demostraría suficiencia pedagógica. Tampoco resolvería los idiomas sin banco bundled.

**Evidencia:** `shared/leveltests/en-leveltests.json`, `shared/leveltests/es-leveltests.json`; `scripts/build_leveltests.py:28–37,73–125`; `services/api/src/level-tests.ts:86–103`; `services/api/src/app.ts:281–317`.

## 5. Qué puede concluirse de la nota actual

### 5.1. Lo que sí hace bien

La nota no es solo un porcentaje global: existen mínimos por sección, secciones débiles y recomendaciones de hasta cuatro unidades asociadas a errores. Esto evita cierta compensación entre habilidades y aporta feedback útil.

La corrección compara con respuestas aceptadas, normaliza mayúsculas/espacios y determinados signos, y admite ciertos placeholders. Es coherente con ejercicios cerrados.

### 5.2. Limitaciones de muestreo y ponderación

Con 25 preguntas de igual peso, reconocimiento representa 48 % de la nota; lectura 24 %, escucha 16 % y escritura 12 %. No hay una justificación empírica de que esa distribución represente preparación integral.

Con los umbrales actuales habituales:

- El global ≥70 requiere 18/25 aciertos: 72 %.
- Reconocimiento ≥60 requiere 8/12.
- Lectura ≥50 requiere 3/6.
- Escucha ≥50 requiere 2/4.
- Escritura ≥50 requiere 2/3.

Un acierto mueve escucha 25 puntos y escritura aproximadamente 33. Los mínimos son útiles, pero con tan pocas observaciones la conclusión por habilidad es muy gruesa. No se debe traducir un 72 % en una probabilidad de aprobar una certificación: no existe esa calibración en el flujo inspeccionado.

**Evidencia:** `services/api/src/level-tests-llm.ts:41–47`; `apps/android/app/src/main/java/com/jkdc/app/core/LevelTests.kt:130–153`; `apps/ios/Core/LevelTests.swift:128–150`.

### 5.3. La escritura cerrada no demuestra redacción

Completar una frase o hacer una transformación sirve para medir control lingüístico. No mide directamente organización de un texto, desarrollo de argumentos, adecuación a destinatario/registro ni síntesis. Una solución natural no incluida en la lista puede fallar por diseño.

Las metas pedagógicas internas ya contemplan relatos, emails, argumentación, reports y síntesis en niveles intermedios/avanzados. El examen actual no recoge una muestra equivalente de producción abierta.

**Evidencia:** `services/api/src/level-tests-llm.ts:151–158`; `apps/android/app/src/main/java/com/jkdc/app/core/ExerciseEngine.kt:3–32`; `docs/plan_mejoras_app_idiomas.md:880–914`.

### 5.4. Expresión oral y condiciones de escucha

La app tiene conversación oral, pero **esa evidencia no forma parte de la calificación semanal inspeccionada**. No puede inferirse dominio de interacción, fluidez o pronunciación desde las cuatro secciones existentes.

En Android, la escucha usa TTS, permite repetición y desplazamiento, y respeta velocidad seleccionada por el usuario. Es útil para practicar y para accesibilidad; no conviene eliminarlo indiscriminadamente. Para comparar preparación hacen falta condiciones equivalentes o registrar explícitamente la asistencia utilizada.

**Evidencia:** `core/LevelTests.kt:17`; `ui/LevelTestScreen.kt:314–343`; `AppViewModel.kt:1611–1647`, bajo `apps/android/app/src/main/java/com/jkdc/app/`; `apps/android/app/src/main/res/values-es/strings.xml:555`.

### 5.5. El mensaje debe corresponderse con la evidencia

Ya existe «Una comprobación semanal, no un certificado». No propongo añadir otro aviso redundante. Sin embargo, el titular «Listo para superar %1$s» y la sugerencia de siguiente banda son más concluyentes que la muestra recogida.

Recomiendo presentar el resultado breve como orientación favorable/desfavorable **en las tareas evaluadas**, mostrando lo no observado. Expresión oral ausente no debe puntuarse como cero: debe figurar como no evaluada.

**Evidencia:** `apps/android/app/src/main/res/values-es/strings.xml:596–602`; `apps/android/app/src/main/java/com/jkdc/app/ui/LevelTestScreen.kt:400–425,459–460`.

## 6. Hallazgos técnicos reproducidos

### H1. El blueprint no gobierna el contenido

**Confirmado.** Se ignoran durante la generación `poolTarget`, `canDoAnchor`, rangos, formato, registro y prohibiciones del modelo. `SKILLS_BY_LEVEL` mantiene otra taxonomía, principalmente orientada al inglés, aplicada también a otros idiomas.

Un contenido elemental pasó como C1 con juez simulado `ok`. Esto no demuestra que un modelo real produzca siempre ese contenido; demuestra que no hay control adicional de nivel más allá del juicio solicitado al LLM.

**Solución:** §8.1. **Pruebas:** R1–R4 de §12.

### H2. Comprobación de idioma latino ineficaz

**Confirmado por código y probe.** En `looksLikeTargetLanguage`, los marcadores de instrucción son subconjuntos de los marcadores del mismo idioma objetivo. La comparación admite `0 >= 0`; ausencia de indicios se convierte en aprobación.

Un banco íntegramente inglés pasó el validador con destino español. Con SQL y juez simulados también pudo llegar a estado aprobado como español.

El validador revisa `prompt + answers`, pero no comprueba así el idioma de pasajes completos ni de explicaciones. Una mezcla de idiomas puede escapar al control.

**Evidencia:** `services/api/src/level-tests-validate.ts:90–91,159–191`. **Solución:** §8.2.

### H3. Chino: validación con estado y longitud incompatible

**Confirmado por probe repetido.** La regex CJK tiene flag global `/g` y se usa con `.test()`, conservando `lastIndex` entre ítems. El mismo banco validado dos veces produjo fallos en posiciones diferentes.

El conteo por espacios considera un párrafo chino natural sin espacios como una palabra y lo rechaza por longitud.

**Evidencia:** `services/api/src/level-tests-validate.ts:44,115–116,167,183`. **Solución:** eliminar estado y definir unidades/rangos por idioma; no inventar una conversión universal carácter-palabra.

### H4. El reintento dirigido no recibe los errores pertinentes

**Confirmado.** El bucle interno asigna `lastReply` con la primera respuesta y termina. El feedback se añade después de llamar, no antes del siguiente intento. Los errores deterministas de contenido aparecen más tarde, fuera de ese bucle.

Una respuesta correcta inexistente entre opciones produjo cuatro llamadas de generación —una por sección— y fallo final, sin repetir reconocimiento ni enviar feedback del problema.

El bucle exterior sí puede repetir excepciones y algunos fallos de pasajes: no es correcto afirmar que no existe ningún reintento. Lo que falta es el anunciado ciclo dirigido por validación de contenido.

**Evidencia:** `services/api/src/level-tests-llm.ts:183–195,294–307,333–337`. **Solución:** §8.3.

### H5. El esquema no garantiza el tipo de tarea de cada sección

**Confirmado mediante entradas controladas:**

| Entrada | Comportamiento observado | Problema |
|---|---|---|
| Escritura formada por opciones múltiples | Aceptada | No garantiza producción, aunque la sección se llame writing. |
| Dos lecturas con mismo texto y preguntas distintas | Aceptadas | Se reduce el número de estímulos distintos. |
| `order` con dos soluciones de una palabra | Aceptado | No aplica la regla editorial de una solución ordenada de al menos tres palabras. |
| Ítem `null` | `TypeError` | Falta error estructurado de validación. |

El validador sí comprueba varias propiedades importantes —respuesta entre opciones, opciones repetidas, explicación, mínimos y cruces lectura/escucha—. No se parte de cero.

La unicidad gramatical de una solución no se puede demostrar simplemente contando una respuesta en el array; exige revisión semántica.

**Evidencia:** `services/api/src/level-tests-validate.ts:67–98,108–140`; `.agents/skills/looro-content/SKILL.md`, reglas de ejercicios. **Solución:** §8.2.

### H6. Juez activo insuficientemente especificado; `strict` conserva dudas

El juez activo solicita respuesta única, distractores incorrectos y dificultad plausible. Envía `explain`, pero no pide expresamente verificar su exactitud; tampoco exige evidencia de comprensión, correspondencia con can-do, registro o cobertura.

Hay un constructor `buildJudgeMessages()` más rico, pero no se invoca. No puede contarse como una garantía del sistema.

`flagged` se admite tanto en generación estricta como en aprobación manual. La combinación `strict + auto` puede publicar un examen con todos los ítems dudosos si lo demás es válido. Existen pruebas que fijan ese comportamiento: **es una política actual, no una regresión que pueda cambiarse sin decisión de producto**.

**Evidencia:** `services/api/src/level-tests-llm.ts:240–249,351–383,402–410`; `services/api/src/level-tests-publication.ts:51–55`; `services/api/tests/level-tests-policy.test.ts:180–189`. **Solución propuesta:** §8.4.

### H7. Ejemplos de referencia incompletos y una prueba engañosa

El few-shot toma siempre los dos primeros ítems por sección de los bancos existentes. Solo hay ejemplos bundled EN/ES A1–B1:

- B2/C1 y otros idiomas carecen de ejemplos de ese banco.
- Lectura entrega ejemplos planos con `passageId`, sin el pasaje fuente.
- Escucha repite el guion por pregunta, en vez de mostrar una tarea completa de comprensión.
- El formato de esos ejemplos no coincide con `{text, items}` solicitado.

No recomiendo aumentar ejemplos indiscriminadamente: primero deben ser completos, representativos y revisados.

Una prueba de integración llamada `strict judge removes rejected items and approves the trimmed exam` utiliza un transporte cuyo juez responde todo `ok`. No ejercita el rechazo que anuncia. Hay otras pruebas de política útiles; el problema es esta falsa señal de cobertura, no ausencia total de pruebas.

**Evidencia:** `services/api/src/level-tests-llm.ts:49–52,114,139,148`; `services/api/tests/level-tests-llm.test.ts:68–81,188–195`. **Solución:** fixtures curadas y pruebas observables del flujo real.

### H8. Uso persistido por generación siempre a cero

Los contadores se inicializan y se persisten, pero no se incrementan. Ocho llamadas simuladas de 100 tokens de entrada y 200 de salida produjeron 0/0 persistidos, frente a 800/1600 devueltos por el transporte.

El transporte sí emite métricas OTel de tokens cuando recibe usage. **No está rota toda la observabilidad**: falla la atribución por generación, justamente la necesaria para presupuestar bancos mayores.

**Evidencia:** `services/api/src/level-tests-llm.ts:290,385–391`; `services/api/src/llm-config.ts:133–139`. **Solución:** §8.6.

## 7. Qué ya está bien y debe conservarse

1. Generación por secciones, que limita tamaño y permite reparar de forma localizada.
2. Validación determinista antes del juez y tras retirar rechazados.
3. IDs asignados por servidor y comprobación de IDs desconocidos/duplicados del juez; completitud estricta.
4. Texto fuente incluido en el juez de comprensión.
5. Composición determinista y golden tests de paridad multiplataforma.
6. Separación de textos de lectura/escucha dentro de cada forma.
7. Snapshots con identidad/hash y separación de revisión de contenido y versión de corrección.
8. Aprobación humana transaccional, auditada y revalidada contra el banco, no solo contra un hash.
9. Estados y endpoints que no sirven borradores como exámenes aprobados.
10. Protección frente a reemisión automática cuando el resultado del proveedor es incierto.
11. Mínimos por sección, historial y feedback de errores.

La recomendación no es reescribir el subsistema. Se trata de extender sus garantías hasta la calidad del contenido y la interpretación del resultado.

**Evidencia:** `services/api/src/level-tests-publication.ts:15–99`; `services/api/src/level-test-snapshot.ts:4–43`; `services/api/src/level-tests-generation-jobs.ts:49–79`; pruebas `level-tests-policy`, `level-tests` y `level-test-snapshot`.

## 8. Implementación detallada: reparar la cadena existente

Esta etapa no cambia las 25 preguntas del examen breve ni su algoritmo histórico de nota. Corrige generación y calidad antes de ampliar el producto.

### 8.1. Un contrato resuelto por idioma, nivel y propósito

**Archivos principales:** `shared/leveltests/exam-models.json`, `scripts/export_leveltests.py`, `services/api/src/level-tests-llm.ts`, `services/api/src/level-tests-validate.ts`, `services/api/src/level-tests-publication.ts`.

**Módulo nuevo propuesto:** `services/api/src/level-test-blueprint.ts`, para resolver/validar el contrato sin mezclarlo con llamadas de red. No es obligatorio ese nombre; sí la responsabilidad única.

Pasos:

1. Tipar los campos de `sections` que hoy son `Record<string, unknown>`: rangos, mezcla de tipos, número de opciones, registro, familias de tarea y capacidades evaluadas.
2. Separar explícitamente `poolTarget` —material válido disponible para componer— de `examShape` —material servido a cada alumno—.
3. Cambiar la resolución a `resolveBlueprint(languageID, level, purpose)`: primero coincidencia exacta de idioma, después wildcard explícito. Hoy `examModelFor(level)` no recibe el idioma; ampliarlo es necesario si se añaden overrides lingüísticos.
4. Mantener capacidades comunicativas comunes, pero definir realizaciones lingüísticas por idioma. No imponer `present perfect`, `cleft sentences` o `phrasal verbs` como taxonomía universal.
5. Pasar el mismo blueprint a generación, validador, juez, compositor y comprobación de publicación. Eliminar constantes duplicadas solo cuando su sustitución esté cubierta por pruebas.
6. Para instrucciones del examen público, recomendar idioma objetivo adaptado al nivel, con apoyo de UI localizado. El prompt actual pide «learner's language» sin recibirlo. Si se necesita instrucción editorial bilingüe, modelarla explícitamente; no inferir la lengua materna.
7. Incrementar `PROMPT_VERSION` y `JUDGE_VERSION` al cambiar instrucciones; separar esa versión de la versión de corrección y del esquema de snapshot.

**No cambiar a mano** `services/api/src/level-tests-content.generated.ts`, `*Data.kt` o `*Data.swift`. El exporter ya incorpora `exam-models.json` al bundle del servidor. Si se modifica su estructura, ampliar la validación del exporter y regenerar mediante `python3 scripts/export_leveltests.py`.

Las restricciones mecánicas deben validarse en código. Las semánticas deben llegar al juez y a revisión docente; no se debe prometer que una regex detecta nivel MCER.

**Aceptación:** cambiar un rango en la fuente canónica cambia los mensajes capturados y los límites de validación correspondientes; A1/C1 producen restricciones distintas; un override de español no termina usando el modelo inglés; una celda no soportada falla explícitamente, sin fallback pedagógico silencioso.

### 8.2. Validación pura, por sección y sin estado

Propuesta de separación interna:

```typescript
validateSection(rawSection, sectionBlueprint, languagePolicy)
validatePool(validSections, resolvedBlueprint)
validateComposedExam(exam, pool, resolvedBlueprint)
```

Son responsabilidades propuestas, no APIs ya existentes.

**Validación estructural:**

- Verificar objeto/array/tipos antes de acceder a propiedades; `null` produce un error con código y localización, no una excepción accidental.
- Comprobar tipos permitidos por sección; la escritura controlada no acepta `choice` si el blueprint exige producción.
- Respetar número de opciones y respuestas según contrato; conservar la pertenencia de la solución a las opciones.
- Aplicar regla de `order` si se mantiene ese tipo: una respuesta y al menos tres palabras.
- Contar preguntas válidas por pasaje después del saneamiento.
- Rechazar duplicados de texto dentro de lectura, dentro de escucha y entre ambas, además de prompts repetidos.
- Utilizar huella completa normalizada para duplicado exacto; un prefijo puede ser un pre-filtro, no evidencia suficiente de igualdad o diferencia.
- Limitar tamaños de arrays/cadenas y salida aceptada de acuerdo con los contratos existentes, antes de almacenar.

**Idioma:**

- Eliminar `/g` del test de presencia CJK, o usar una operación sin estado compartido.
- Separar idioma del contenido de idioma de instrucciones.
- Sustituir la comparación tautológica por un resultado explícito `pass | fail | unknown` basado en evidencia real. No tratar cero marcadores como aprobación.
- Comprobar pasaje y explicación, además de pregunta/respuesta. Permitir palabras prestadas, nombres propios y respuestas breves legítimas.
- Para casos ambiguos, elevar revisión; no declarar automáticamente que están mal ni bien.
- No añadir una librería de detección por reflejo: primero evaluar con corpus positivo/negativo por idioma. Si el detector no rinde en frases breves, usarlo para pasajes y dejar esas frases al control contextual.

**Longitud:**

- Hacer explícita la unidad en la política de idioma: palabras segmentadas, caracteres u otra medida acordada.
- Para chino, elegir y documentar una medida reproducible y rangos propios. Si se usa segmentación, fijar su comportamiento con goldens; no heredar sin más los intervalos ingleses.
- Probar límites inferior/superior incluidos y un caso inmediatamente fuera de cada límite.

**Aceptación:** validación idéntica para la misma entrada, independientemente del orden y de llamadas previas; inglés etiquetado como español no pasa sin revisión; un párrafo chino natural no se convierte en «una palabra» por carecer de espacios; el input inválido produce errores deterministas y no publicaciones parciales.

### 8.3. Un ciclo de reparación acotado por sección

Eliminar los dos bucles superpuestos y centralizar el presupuesto:

```text
para cada sección:
  construir mensajes desde blueprint y ejemplos
  generar → parsear → validar sección
  si hay errores reparables y queda el único intento correctivo:
    añadir errores y requisitos concretos ANTES de llamar de nuevo
    reparar la sección o su déficit
    validar el resultado completo de esa sección
  si sigue inválida: terminar sin publicar

validar invariantes globales del banco
ejecutar juez
retirar rechazos y comprobar material/cobertura disponibles
componer solamente si siguen cumpliéndose todos los requisitos
```

Detalles importantes:

1. Mantener las secciones válidas; un fallo de escritura no regenera lectura.
2. Feedback con códigos y localizadores seguros: `recognition[3].answer_not_in_options`, no volcados de excepciones, configuración o claves.
3. Una reparación que añade material debe evitar duplicados contra las secciones ya aceptadas. Revalidar globalmente después.
4. Conservar **un único presupuesto correctivo por sección** que incluya reparación estructural y eventual reposición tras juez. No introducir un segundo bucle ilimitado de «hasta que apruebe».
5. Todo material añadido debe pasar las mismas puertas; revisar únicamente lo nuevo puede ser suficiente si se prueba que lo anterior no cambió. Si se sustituye un pasaje, revisar sus preguntas de nuevo.
6. Ante `llm_outcome_unknown` o cancelación, detenerse y conservar el estado conocido. No reintentar automáticamente una llamada cuyo resultado puede haberse producido y facturado.
7. Si el material no alcanza el contrato tras el presupuesto, devolver fallo/borrador no publicable con motivo; nunca rellenar con preguntas repetidas o bajar mínimos en silencio.

**Aceptación:** primer reconocimiento inválido, segundo válido: dos llamadas a esa sección, feedback concreto y una llamada a cada otra sección; segundo inválido: sin publicación; resultado incierto: sin segunda llamada.

### 8.4. Rúbrica del juez y política de dudas

**Archivos:** `level-tests-llm.ts`, `level-tests-publication.ts`, pruebas de política y panel admin. Reutilizar el constructor activo; retirar `buildJudgeMessages()` muerto tras migrar las garantías útiles.

La revisión debe comprobar, con códigos cerrados:

| Dimensión | Evidencia solicitada |
|---|---|
| Corrección | Respuesta válida y ausencia de otra opción igualmente válida. |
| Comprensión | Fragmento/localización que sustenta la solución; inferencia explicada cuando no es literal. |
| Explicación | No contradice regla, texto ni solución. |
| Nivel y capacidad | Tarea compatible con descriptor y demanda prevista; no solo vocabulario aparentemente avanzado. |
| Distractores | Plausibles y descartables por el contenido, no absurdos ni dependientes de conocimiento externo. |
| Producción cerrada | Variantes razonables aceptadas y consigna suficientemente acotada. |
| Pasaje | Registro, formato oral/escrito, coherencia y restricciones del blueprint. |

Ejemplo de resultado propuesto, manteniendo IDs asignados por servidor:

```json
{
  "items": [{
    "id": "rea-0-1",
    "verdict": "reject",
    "reasonCode": "answer_not_supported",
    "reason": "The passage does not state the claimed arrival time.",
    "evidence": "The relevant sentence gives a different time."
  }]
}
```

No solicitar razonamiento interno extenso. Una evidencia breve y verificable permite revisar el juicio sin aumentar innecesariamente tokens. Si el proveedor soporta salida estructurada, puede utilizarse como ayuda, pero la validación local sigue siendo obligatoria y debe existir para todos los transportes soportados.

**Política recomendada para contenido destinado a diagnóstico:**

| Resultado | Destino propuesto |
|---|---|
| `ok` | Elegible, sujeto a los demás requisitos. |
| `reject` | Excluir; reponer dentro del presupuesto o declarar insuficiencia. |
| `flagged` | No autoaprobar; reparación o resolución humana registrada. |
| Juez incompleto/indisponible | No tratarlo como evidencia favorable. |

Esto modifica una política existente. Antes de aplicarlo hay que acordar si afecta solo al diagnóstico o también al examen breve. No cambiar silenciosamente el significado global de `strict` ni activar/desactivar modos del despliegue durante una corrección técnica.

La resolución humana debe identificar el ítem, motivo, revisor y revisión de contenido aprobada. No basta con un clic global que haga desaparecer las dudas. Si ya existe registro de moderación, ampliarlo en lugar de crear otro sistema paralelo.

**Calibración:** preparar un corpus docente de ejemplos buenos y defectuosos, incluyendo ambigüedad, explicación falsa, nivel fuera de banda, variantes incompletas e inferencia no sustentada. Medir falsos negativos y falsos positivos por idioma/nivel/categoría. Una segunda llamada al mismo modelo no constituye validación psicométrica independiente.

### 8.5. Banco, variantes y cobertura de capacidades

Primero usar el `poolTarget` ya declarado: 24/6/4/4 para el examen breve. Aumenta la reserva sin cambiar duración del alumno. Verificar ese objetivo **después** de saneamiento y rechazos, o marcar explícitamente que una ejecución degradada no cumple la política de diversidad.

No confundir:

- **Mínimo estructural:** material para una forma.
- **Objetivo de banco válido:** reserva y diversidad prometidas.
- **Equivalencia de formas:** misma distribución de capacidades y demanda, no mismas preguntas.

Añadir metadatos donde sean consumidos realmente:

```typescript
type AssessmentEvidence = {
  assessedSkills: string[];
  canDoIds: string[];
  taskFamily: string;
  stimulusId?: string;
  comprehensionFocus?: 'gist' | 'detail' | 'inference' | 'intention';
};
```

Es un esquema ilustrativo. Validar referencias contra el catálogo existente y no contar `exposureSkills` como dominio evaluado. Evitar una segunda lista de IDs incompatibles con `shared/skills/`.

El campo `RawGeneratedItem.skill` ya existe, pero el normalizador no lo conserva; añadirlo únicamente al prompt no funcionaría. El prompt tampoco solicita `unitID`, de modo que no hay que asumir que todas las preguntas generadas producen recomendaciones por unidad.

**Composición propuesta:**

1. Seleccionar por cuotas de capacidades y familias de tarea definidas para la forma.
2. Dentro de candidatos equivalentes, conservar selección determinista con semilla/versionado.
3. Evitar varias paráfrasis de la misma regla como sustituto de cobertura.
4. Medir intersección de IDs y de estímulos entre variantes. Dos preguntas diferentes del mismo texto no son dos estímulos nuevos.
5. Establecer explícitamente el máximo solapamiento deseado; verificarlo en vez de asumirlo por el hash.
6. Si no hay solución que cumpla cobertura/diversidad, no prometer formas independientes. Reponer material o declarar que son variantes de orden.

La independencia completa no es necesaria para toda práctica. Para diagnóstico repetido sí hay que evitar presentar repetición como nueva evidencia. Puede bastar registrar exposición a estímulos y excluirla del cómputo de evidencia nueva; no hace falta implantar un gran motor de antirrepetición histórica antes de reparar el banco mínimo.

**Compatibilidad:** los metadatos pueden empezar en banco/revisión internos. Para enviarlos al móvil hay que actualizar tipos/exporters, lectores y allowlists de sincronización. `services/api/src/level-test-attempts.ts:21–22,104–113` rechaza campos de ítem no permitidos: no es una ampliación inocua del JSON.

### 8.6. Uso, presupuesto y trazabilidad

Envolver el transporte común de una ejecución para sumar usage de generación, juez y reparaciones. Evitar sumar en dos capas. Registrar duración y llamadas por fase con valores de cardinalidad cerrada; no introducir prompts, respuestas de alumnos ni PII en telemetría.

Los ceros actuales no permiten estimar el coste real. Si el proveedor omite usage, registrar «desconocido/parcial», no cero confirmado. El tipo actual `ChatReply` convierte ausencia de usage en cero: para distinguir ambos casos hay que preservar esa información en el transporte, sin romper sus consumidores.

Preservar uso conocido también en ejecuciones fallidas mediante el registro de runs existente; no crear una fila de examen aprobado para conservar un contador. Un resultado incierto puede tener coste desconocido.

Conservar por ejecución versiones de prompt/juez y un identificador/hash del blueprint utilizado. Si se añade persistencia, usar una migración nueva —nunca editar migraciones aplicadas—. Para registros históricos sin ese dato, marcar `legacy`, no inventar un blueprint retrospectivo.

**Importante:** la publicación de un borrador histórico debe usar su contrato congelado, no reinterpretarlo silenciosamente con los nuevos rangos. Hoy `canPublish()` consulta el modelo actual por nivel. Hay que definir una política explícita de publicación de borradores legacy; los snapshots ya publicados y sus intentos no se reescriben.

**Presupuesto:** banco doble no significa exactamente doble factura: hay contexto fijo, preguntas adicionales, juez y posibles reparaciones. Medir coste/latencia por ejecución y material finalmente utilizable antes de ampliar todos los idiomas/niveles. No cambiar de proveedor como primera solución.

### 8.7. Ejemplos curados y mantenimiento de la skill editorial

Crear ejemplos revisados por idioma/nivel/sección cuando se habilite esa celda. Para comprensión, un ejemplo debe contener pasaje completo, todas sus preguntas, respuestas y evidencia; no un `passageId` huérfano.

No tomar siempre los primeros dos ítems de un banco de enseñanza por accidente. No son necesariamente una muestra representativa de examen. Evitar copiar errores del material fuente como referencia de estilo.

Mantener la skill editorial enfocada. Una mejora pequeña y justificada sería documentar:

- Diferencia entre contenido docente, examen breve y diagnóstico.
- Que los prompts runtime están en la API y no cargan esa skill.
- Invariantes de exportación y pruebas específicas de exámenes.
- Revisión de ejemplos antes de promoverlos a referencia.

No hace falta reescribirla por estilo, añadir metadatos cosméticos ni convertir instrucciones de commits/exporters en prompt para el alumno.

## 9. Implementación pedagógica: un diagnóstico de preparación distinto

### 9.1. Definir la promesa antes de aumentar preguntas

Para este informe se propone **diagnóstico general alineado con MCER**, no una réplica de una certificación concreta. Si el objetivo real es DELE, Cambridge, DELF u otra, hay que crear un perfil de examen específico por idioma/titulación: un nivel B2 no determina por sí solo formato, tiempos, géneros y reglas de nota.

El Consejo de Europa describe el enlace con MCER como un proceso que necesita justificación y evidencia, no una etiqueta añadida al contenido. El marco no valida automáticamente los exámenes de una aplicación. [Manual para relacionar exámenes con el MCER](https://www.coe.int/en/web/common-european-framework-reference-languages/relating-examinations-to-the-cefr).

Como referencia de escala, B2 First incluye 52 preguntas de Reading/Use of English, 30 de escucha, dos producciones escritas y una prueba oral; C1 Advanced incorpora 56 preguntas de Reading/Use of English y 30 de escucha, además de escritura y expresión oral. **No son mínimos universales que Palique deba copiar**, pero muestran por qué el actual examen de 25 respuestas cerradas no puede anunciarse como simulacro equivalente. [Handbook oficial B2 First](https://www.cambridgeenglish.org/Images/167791-b2-first-handbook.PDF), [formato oficial C1 Advanced](https://www.cambridgeenglish.org/exams-and-tests/qualifications/advanced/format/?rows=24).

### 9.2. Mantener dos propósitos

| Propósito | Diseño | Qué puede afirmar |
|---|---|---|
| Comprobación semanal | Forma breve actual, mejorada y con feedback | Rendimiento en una muestra concreta y qué reforzar. |
| Diagnóstico de preparación | Más estímulos, cobertura explícita, producción escrita/oral y evaluación calibrada | Perfil de preparación por capacidades, con límites e incertidumbre. |

Ventaja: la práctica frecuente sigue siendo asumible. Coste: aparece un recorrido adicional con evaluación y mantenimiento propios. Es preferible a aumentar indiscriminadamente la duración de la actividad semanal y mantener las mismas carencias de cobertura.

### 9.3. Cantidad inicial propuesta para pilotar

**Hipótesis de diseño, no estándar validado ni cifra óptima demostrada.** La cantidad final debe depender de cobertura, tiempo y estabilidad observados. La evidencia disponible no permite declarar honestamente que «X preguntas garantizan el nivel».

Un primer blueprint de diagnóstico podría comenzar con:

| Nivel | Reconocimiento | Lectura | Escucha | Producción controlada | Total cerrado | Producción abierta adicional |
|---|---:|---|---|---:|---:|---|
| A1 | 12 | 3 textos × 3 | 3 estímulos × 3 | 4 | 34 | 1 texto funcional breve; 2 tareas orales breves. |
| A2 | 14 | 3 × 4 | 3 × 3 | 4 | 39 | 1 texto funcional/narrativo; 2 tareas orales. |
| B1 | 16 | 3 × 4 | 3 × 4 | 4 | 44 | 2 textos de propósitos distintos; 3 tareas orales. |
| B2 | 18 | 4 × 4 | 4 × 4 | 4 | 54 | 2 textos con destinatario/registro; 3 tareas orales. |
| C1 | 18 | 4 × 5 | 4 × 4 | 4 | 58 | 2 textos con argumentación/síntesis; 3 tareas orales. |

No convertir esta tabla directamente en constantes de producción. Primero aprobar la cobertura que pretende medir y pilotar el cansancio/tiempo. El incremento relevante son más tareas y contextos, no solo más respuestas sobre el mismo pasaje.

Para longitud, mantener inicialmente los rangos vigentes del examen breve una vez conectados. En el diagnóstico, definir por **familia de tarea**, no un mínimo único por nivel:

- A1/A2: avisos, mensajes, instrucciones y descripciones; no alargar un aviso artificialmente para cumplir palabras.
- B1: combinar textos funcionales con relato/opinión y comprensión de intención.
- B2/C1: incorporar textos sostenidos, contraste de puntos de vista, actitud e inferencia; al menos una tarea no resoluble por localizar una frase literal.
- Para inglés B2/C1, puede pilotarse un texto sostenido de 300–500 / 450–700 palabras respectivamente, junto con otros más breves; son propuestas editoriales, no rangos oficiales ni garantías de dificultad.
- En escucha, especificar también duración, participantes, registro y condiciones de reproducción. Las palabras del guion no bastan para describir la exigencia auditiva.

Si la sesión resulta demasiado larga, dividir módulos manteniendo su identidad y condiciones; no eliminar silenciosamente habilidades y conservar el mismo titular de preparación integral.

### 9.4. Escritura abierta y expresión oral

**Escritura:** consigna con contexto, propósito, destinatario, género y extensión orientativa por tarea. Evaluar dimensiones separadas: cumplimiento, organización/cohesión, adecuación y recursos/corrección lingüística. Una sola respuesta modelo es ejemplo, no lista exhaustiva de soluciones correctas.

**Expresión oral:** recoger al menos producción individual y una tarea que requiera responder/interactuar. Evaluar cumplimiento, coherencia, rango/control, fluidez e inteligibilidad. No confundir precisión de transcripción con calidad oral, ni acento no nativo con falta de inteligibilidad.

**Evaluador:** rúbricas versionadas con anclas docentes por nivel. Inicialmente, evaluación asistida y revisión de muestra/discrepancias. Si un modelo evalúa, devolver dimensiones, evidencias breves y estado; un fallo técnico produce `pending_review` o `inconclusive`, no una nota cero ni un aprobado por defecto.

No introducir escritura/oral como `written` con comparación exacta. Requieren tipos de tarea/evaluación propios y contratos nuevos compatibles con el historial.

**Datos y privacidad:** antes de activar envío de redacciones o audio a un proveedor, definir consentimiento, destino, conservación, borrado y acceso. No enviar conversaciones históricas completas por comodidad ni incluir respuestas personales en logs. Esto es una condición de implementación futura, no una operación autorizada por este informe.

### 9.5. Resultados e incertidumbre

El resultado de diagnóstico debería mostrar:

- Nivel objetivo y tipo de diagnóstico, sin atribuirle carácter oficial.
- Perfil por capacidad y dimensión evaluada.
- Cantidad de tareas y estímulos distintos que sustentan cada resultado.
- Cobertura pendiente, asistencia de escucha y posible repetición de material.
- Recomendaciones vinculadas a capacidades/can-do y unidades cuando haya correspondencia real.
- Estado no concluyente cuando falte evidencia o haya evaluación pendiente.

No cambiar automáticamente «70 %» por otro umbral arbitrario. El punto de corte necesita revisión de docentes y comprobación con alumnos de preparación conocida; si se pretende estimar una probabilidad de aprobar un examen externo, hace falta una validación específica contra ese resultado.

Tampoco añadir intervalos binomiales como si todas las preguntas fueran independientes: las de un mismo texto están agrupadas. Un análisis de incertidumbre debe considerar tareas/estímulos y datos reales.

El manual ALTE/Consejo de Europa de 2026 organiza la calidad alrededor de especificaciones, control de tareas, pilotaje, calificación y revisión continua. Esa es la base de la recomendación de pilotar; **ningún test unitario sustituye esa evidencia**. [Manual revisado de desarrollo de pruebas, 2026](https://rm.coe.int/new-revised-manual-for-language-test-development-and-examining-joint-p/48802cc30d).

## 10. Compatibilidad, almacenamiento y despliegue

### 10.1. No romper el examen v1

Mantener generación/serving v1 y calificación histórica. Versionar por separado:

| Versión | Qué identifica |
|---|---|
| Esquema | Campos y tipos que entiende el consumidor. |
| Blueprint | Propósito, cobertura y composición. |
| Prompt/juez | Instrucciones que generaron y revisaron material. |
| Contenido | Preguntas, pasajes y tareas concretas. |
| Corrección/rúbrica | Cómo se interpretan respuestas y puntuaciones. |

No recalificar intentos antiguos con una rúbrica nueva ni sustituir sus snapshots. No presentar porcentajes v1 y diagnósticos nuevos como puntuaciones directamente equivalentes.

### 10.2. Superficies que realmente cambian

| Área | Cambios necesarios |
|---|---|
| Fuente canónica y exporters | Modelo tipado, validación y ejemplos/metadatos canónicos. |
| Generación API | Resolución de blueprint, mensajes, validación por sección, reparación y usage. |
| Publicación/admin | Política de dudas, contrato congelado, resumen de cobertura y diversidad; resolución auditable. |
| Snapshot/sincronización | Allowlist y validación versionadas si se envían campos nuevos. |
| Android/iOS Core | Nuevos tipos/evaluación sin alterar lectores ni grading v1. |
| UI | Recorrido de diagnóstico, estados pendientes/no evaluados, revisión por dimensión. |
| Persistencia | Migraciones nuevas solo para los datos nuevos necesarios; conservación de identidad histórica. |

Para el diagnóstico, proponer recursos nuevos versionados —por ejemplo, `/v2/readiness-exams` y `/v2/readiness-attempts`— o una negociación explícita de capacidad. Los nombres son ilustrativos. **No añadir `speaking` a un enum cerrado v1 y esperar que los clientes antiguos lo ignoren**.

La corrección de producción abierta puede ser asíncrona y separada de la finalización local de preguntas cerradas. Persistir de forma idempotente: intento, revisión de tarea, versión de rúbrica, estado y resultado por dimensión. Un retry técnico no crea una segunda evaluación facturable sin comprobar su identidad/estado.

Los límites existentes también importan: los snapshots actuales restringen campos, longitud de respuestas, tipos, hasta 100 ítems y listas referenciadas. No basta con subir `examShape`; hay que verificar contratos completos y tamaño de payload.

**Evidencia:** `services/api/src/level-test-attempts.ts:15–22,78–113`; `services/api/src/level-test-snapshot.ts:4–43`; tipos `LevelTestSection` de ambos cores.

### 10.3. Despliegue recomendado

1. Reparar validación/generación con pruebas y mantener por defecto revisión humana.
2. Generar nuevas revisiones con versiones nuevas; revisar borradores legacy de forma explícita.
3. Validar una muestra real exportada por un operador autorizado: tamaño, idioma, claves, cobertura y dudas. No usar este informe como prueba de calidad del inventario publicado.
4. Pilotar una celda acotada por idioma/nivel; no activar 40 celdas como si una validación inglesa demostrara todas.
5. Ejecutar evaluación de diagnóstico en sombra antes de que influya en recomendaciones.
6. Comparar juicio docente y evaluación automatizada, tiempos, abandono y estabilidad entre formas.
7. Ampliar gradualmente cuando se cumplan criterios de calidad definidos previamente. Conservar rollback a selección/serving anterior, sin borrar intentos ni mutar contenido aprobado.

## 11. Secuencia de trabajo propuesta

No se ha iniciado ninguna de estas implementaciones. Para cambios significativos, mantener SDD bajo `openspec/changes/`, según la política del repositorio, y trabajar en la rama activa con Superpowers; sin cambiar rama, crear worktrees, publicar ni abrir PR como condición para continuar.

| Entrega | Alcance y archivos | Resultado verificable |
|---|---|---|
| E1 | Regresiones de idioma/CJK/esquema en `level-tests-llm.test.ts` o tests puros nuevos; `level-tests-validate.ts` | Casos reproducidos pasan a errores estructurados/deterministas, sin falsos rechazos evidentes del corpus. |
| E2 | Blueprint tipado, JSON/exporter y consumidores | A1/C1 usan sus reglas; idioma exacto precede wildcard; los generados no se editan a mano. |
| E3 | Generación por sección y reparación acotada | Feedback enviado antes del retry; solo sección afectada; sin reemisión de resultado incierto. |
| E4 | Banco doble, composición por requisitos y uso por ejecución | Variantes no idénticas cuando se promete diversidad; medición de solapamiento; tokens conocidos sumados. |
| E5 | Rúbrica activa, ejemplos, publicación/admin | Dudas gestionadas según política acordada; referencias completas; rechazo probado de verdad. |
| E6 | Metadatos de cobertura y feedback breve | No se pierden skills; sincronización compatible; resultado no sobrerrepresenta capacidades ausentes. |
| E7 | Diagnóstico nuevo, tareas productivas y rúbricas | Contratos/versionado propios, fallos no concluyentes e historial v1 intacto. |
| E8 | Piloto docente y alumnado | Evidencia de adecuación, concordancia y tiempo antes de anunciar preparación integral. |

E1–E5 son la mejora técnica prioritaria. E6–E8 son lo que convierte la mejora de generación en una evaluación de preparación más defendible. No recomendaría empezar por E7 dejando intactas las puertas defectuosas.

**Decisiones de producto pendientes para esa segunda etapa:** diagnóstico general o certificación concreta; cobertura y tiempo tolerable; política de dudas/autopublicación; tratamiento de datos de producción escrita/oral. Se documentan aquí para una implementación posterior, sin bloquear el informe con un cuestionario.

## 12. Plan de pruebas y criterios de aceptación

### 12.1. Regresiones deterministas: RED → GREEN

Escribir primero una prueba que falle por cada regla corregida. Capturar mensajes y respuestas mediante transporte simulado, sin depender de frases frágiles del prompt para distinguir generación y juez.

| ID | Caso | Comprobaciones obligatorias |
|---|---|---|
| R1 | A1 y C1 desde sus blueprints | Tamaños, rangos, ancla y restricciones presentes en mensajes reales. |
| R2 | `poolTarget` distinto de `examShape` | Se solicita/persiste el banco objetivo y se sirve la forma prevista. |
| R3 | Pool mínimo frente a pool doble | Mínimo no se anuncia como diversidad; doble cumple la política elegida; hash reproducible. |
| R4 | Override por idioma | Español/francés no heredan reglas inglesas por orden de búsqueda. |
| R5 | Inglés etiquetado como español | Rechazado o pendiente, no aprobación por ausencia de marcadores. |
| R6 | Chino, misma entrada dos veces y en distinto orden | Veredictos equivalentes; sin dependencia de `lastIndex`. |
| R7 | Texto chino natural y límites de longitud | Unidad correcta, límites inclusivos y errores de fuera de rango. |
| R8 | `null`, tipos incorrectos y arrays inválidos | Error estructurado, sin crash accidental ni persistencia. |
| R9 | Escritura `choice`, `order` inválido, texto duplicado | Reglas de sección y estímulo aplicadas. |
| R10 | Primer intento inválido, segundo válido | Feedback concreto antes de llamada; solo sección afectada repetida. |
| R11 | Dos intentos inválidos / resultado incierto | No publicación; presupuesto respetado; incierto no se reemite. |
| R12 | `reject` con reserva suficiente/insuficiente | Ítem fuera de pool/variantes; forma completa o fallo, nunca relleno silencioso. |
| R13 | `flagged`, juez incompleto, IDs duplicados/desconocidos | Política explícita y misma interpretación en generación/publicación. |
| R14 | Uso de generación, juez, reparación y error | Suma exacta de usage conocido; ausencia no presentada como cero confirmado. |
| R15 | Capacidad inexistente o cobertura insuficiente | Error con motivo; no se descartan metadatos silenciosamente. |
| R16 | Publicación rechazada | Sin cambio de estado/datos de contenido; auditoría coherente. |
| R17 | Snapshot/intent v1 antes y después | Mismos hashes y resultados históricos; lectores antiguos conservados. |
| R18 | Tareas abiertas y fallo de evaluación | No exact-match; estado pendiente/no concluyente; no cero automático. |

Las pruebas de reparación deben comprobar resultado, número/orden de llamadas y estado persistido. Un test llamado «removes rejected items» debe producir realmente un rechazo y afirmar que el ID no aparece en ninguna variante servida.

### 12.2. Integración y contratos

Una vez implementados los cambios:

```bash
# Desde la raíz: banco/exporters y paridad
python3 scripts/export_leveltests.py --check
make check

# Backend en PostgreSQL efímero; no usar una BD personal/producción
make test-api

# Type-check local, desde services/api
./node_modules/.bin/tsc --noEmit

# Cuando se modifiquen cores/UI
make android-build
swift test --package-path apps/ios
```

Los comandos anteriores son el plan de verificación futura; **no todos se ejecutaron en esta auditoría**. Swift Core puede verificarse por separado, pero el target UI iOS requiere macOS/Xcode. La integración Docker necesita su entorno y tiene efectos locales: no debe confundirse con el set sin BD utilizado aquí.

En E2E futuro, usar `E2E_EVIDENCE_DIR` dentro del directorio ignorado del SDD y no sobrescribir capturas versionadas accidentalmente.

### 12.3. Validación pedagógica que no puede sustituirse por tests

Preparar una matriz por idioma/nivel de:

- Tareas correctas y defectos introducidos conocidos.
- Respuestas de alumnos de distinta preparación, anonimizadas y autorizadas.
- Juicio de revisores competentes con rúbrica y ejemplos ancla.
- Concordancia y discrepancias por dimensión; adjudicación de desacuerdos.
- Rendimiento de distractores, dificultad observada y repetición de estímulos.
- Tiempo, abandono y condiciones de asistencia.

Establecer tamaño de muestra y criterios con quien sea responsable de evaluación. Un pequeño corpus sirve para depurar fallos evidentes; no permite anunciar porcentajes precisos de fiabilidad. No fijo aquí una muestra universal ni una tasa de acierto aceptable sin evidencia.

## 13. Verificación efectivamente ejecutada

### 13.1. Tipos y selección de pruebas existentes

Desde `services/api`, Node `v22.22.1`:

```bash
./node_modules/.bin/tsc --noEmit

env -u TEST_DATABASE_URL -u DATABASE_URL \
  node --import tsx --test --test-concurrency=1 \
  tests/level-tests.test.ts \
  tests/level-tests-llm.test.ts \
  tests/level-tests-policy.test.ts \
  tests/level-tests-scheduler.test.ts \
  tests/level-tests-generation-jobs.test.ts
```

| Comprobación | Resultado observado |
|---|---|
| Type-check | Exit 0, sin errores. |
| Selección de pruebas | 68 registradas: **55 pasadas, 0 fallidas, 13 omitidas**. |
| Motivo de omitidas | Requieren `TEST_DATABASE_URL`; no se conectó una BD. |
| `python3 scripts/export_leveltests.py --check` | Exit 0: `level test data up to date`. |
| Conteo de blueprints y bancos | Tablas de §4 reproducidas desde JSON. |
| Probes de validador | Confirmaron idioma, duplicado de lectura, escritura choice, `null`, CJK con estado y longitud por espacios. |
| Probes de flujo con stubs | Confirmaron variantes con conjunto idéntico, ausencia de retry dirigido y tokens persistidos 0/0. |
| Snippets de §13.2 y §13.3 leídos desde este documento | Ambos ejecutados con exit 0; salidas del probe confirmadas. |

Los tests verdes son compatibles con los defectos: varios casos no están cubiertos y las pruebas técnicas no pretenden certificar calidad pedagógica. No se ha ejecutado la suite completa Docker, Android ni iOS, ni una generación real con modelo.

### 13.2. Reproducir el inventario sin regenerar archivos

Desde la raíz; solo lectura:

```bash
python3 -B - <<'PY'
import collections
import json
import statistics

for language in ('en', 'es'):
    with open(f'shared/leveltests/{language}-leveltests.json') as source:
        bank = json.load(source)
    for level in bank['levels']:
        lengths = [len(p['text'].split()) for p in level['passages']]
        print(language, level['level'],
              dict(collections.Counter(i['section'] for i in level['pool'])),
              'texts', len(lengths),
              'words', min(lengths), round(statistics.mean(lengths), 1),
              max(lengths))

with open('shared/leveltests/exam-models.json') as source:
    for model in json.load(source)['levels']:
        print(model['level'], model['poolTarget'], model['examShape'],
              model['sections']['reading']['words'],
              model['sections']['listening']['words'])
PY
```

### 13.3. Probe mínimo de validación

Desde `services/api`; no lee entorno de proveedores, no conecta BD y no escribe contenido:

```bash
node --import tsx --input-type=module <<'JS'
import { validateGeneratedPool } from './src/level-tests-validate.ts';

const shape = {
  recognition: 12, writing: 3, readingPassages: 2,
  readingPerPassage: 3, listeningPassages: 2, listeningPerPassage: 2,
};
const item = id => ({
  kind: 'choice', prompt: `The teacher is asking question ${id}.`,
  options: ['yes', 'no', 'maybe'], answers: ['yes'],
  explain: 'The text gives this answer.',
});
const text = id => `${id}. ${
  'The teacher is at the school and the students are there today. '.repeat(4)
}`;
const make = () => ({
  recognition: Array.from({ length: 12 }, (_, i) => item(`R${i}`)),
  writing: Array.from({ length: 3 }, (_, i) => ({
    ...item(`W${i}`), kind: 'written', options: [], answers: ['yes'],
  })),
  reading: Array.from({ length: 2 }, (_, i) => ({
    text: text(`Reading ${i}`),
    items: Array.from({ length: 3 }, (_, j) => item(`R${i}Q${j}`)),
  })),
  listening: Array.from({ length: 2 }, (_, i) => ({
    text: text(`Listening ${i}`),
    items: Array.from({ length: 2 }, (_, j) => item(`L${i}Q${j}`)),
  })),
});

console.log('English accepted as Spanish:',
  validateGeneratedPool(make(), shape, 'es').ok);

const duplicate = make();
duplicate.reading[1].text = duplicate.reading[0].text;
console.log('Repeated reading text accepted:',
  validateGeneratedPool(duplicate, shape, 'en').ok);

const zh = make();
const all = [...zh.recognition, ...zh.writing,
  ...zh.reading.flatMap(p => p.items),
  ...zh.listening.flatMap(p => p.items)];
all.forEach(i => { i.prompt = `中 ${i.prompt}`; });
const failures = () => validateGeneratedPool(zh, shape, 'zh')
  .errors.filter(e => e.code === 'language').map(e => e.section);
console.log('Identical input deterministic:',
  JSON.stringify(failures()) === JSON.stringify(failures()));
JS
```

Resultado observado en la revisión analizada: `true`, `true`, `false`. Es un probe de garantías estructurales, **no un ejemplo de contenido pedagógicamente válido**. Después de corregir el código, esas salidas deberían cambiar y los casos convertirse en pruebas permanentes.

## 14. Qué no recomiendo

- **No pedir 100 preguntas por sistema.** Aumentaría coste y cansancio sin arreglar cobertura, producción ni validez.
- **No sustituir el proveedor como primera medida.** Ningún modelo puede aplicar sistemáticamente reglas que no recibe ni crear reserva que no se solicita.
- **No añadir RAG/multiagentes por defecto.** Los defectos principales son contratos desconectados y pruebas insuficientes de reglas concretas.
- **No afirmar que falta juez, panel o hash.** Ya existen y aportan garantías importantes.
- **No imponer siempre tres variantes disjuntas.** Tiene coste; la necesidad depende del propósito. Sí hay que medir y declarar la diversidad real.
- **No tratar `flagged` como un bug accidental de strict.** Cambiarlo implica una política de publicación explícita.
- **No usar exact-match para valorar redacciones o expresión oral.** Son tareas y rúbricas diferentes.
- **No modificar contenido aprobado, migraciones antiguas ni resultados históricos.** Crear nuevas revisiones y conservar identidad.
- **No prometer aprobación oficial o dominio integral a partir de una muestra parcial.** El resultado debe decir qué se observó y qué falta por observar.

## 15. Siguiente paso recomendado

Implementar E1–E5: validación correcta, blueprint realmente aplicado, reparación acotada, banco con reserva y juez verificable. Después revisar una muestra real de publicaciones con docentes competentes y decidir el blueprint del diagnóstico.

**Respuesta a la preocupación original:** el contenido servido es reducido para sostener una evaluación integral, especialmente en niveles avanzados, y hay una reducción adicional no intencionada del banco solicitado. La mejora defendible consiste en corregir esa reducción, exigir cobertura y recoger producción real; no en inflar el número de preguntas y mantener las mismas inferencias.
