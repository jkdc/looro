# Plan de mejoras del sistema de aprendizaje de idiomas

## 1. Objetivo general

La aplicación debe evolucionar desde un sistema de lecciones lineales hacia una plataforma de aprendizaje capaz de llevar a un usuario desde nivel cero hasta un dominio avanzado y profesional de cada idioma.

El objetivo no debe ser únicamente que el usuario "complete lecciones", sino que la aplicación pueda responder con precisión a preguntas como:

- Qué sabe hacer el usuario.
- Qué estructuras domina.
- Qué vocabulario tiene consolidado.
- Qué errores repite.
- Qué habilidades necesita practicar.
- Qué nivel CEFR está preparado para afrontar.
- Qué debería estudiar hoy.
- Qué contenidos necesita volver a practicar dentro de unos días.
- Qué tan bien comprende, habla, lee y escribe el idioma.

La arquitectura actual ya tiene varias piezas valiosas:

- JSON canónicos como fuente de contenido.
- Jerarquía Curso → Unidad → Lección.
- Ejercicios deterministas.
- Listening.
- Tutor conversacional con IA.
- Recall y repetición espaciada.
- Ledger de actividad.
- Motores derivados de progreso y nivel.
- Sincronización entre dispositivo y servidor.

La recomendación es conservar estas piezas y evolucionarlas hacia un sistema de aprendizaje basado en competencias y evidencia de dominio.

---

# 2. Principio fundamental: separar contenido, progreso, dominio y gamificación

Actualmente varias de estas ideas están demasiado relacionadas entre sí.

En el nuevo sistema deben existir cuatro conceptos claramente separados.

## 2.1 Contenido

Representa lo que existe dentro del curso:

- unidades;
- lecciones;
- listenings;
- ejercicios;
- conversaciones;
- lecturas;
- actividades de pronunciación;
- checkpoints;
- repasos.

## 2.2 Progreso del curso

Representa cuánto contenido ha recorrido el usuario.

Ejemplos:

- 18 de 40 lecciones completadas;
- 4 de 10 unidades terminadas;
- 62 % del curso A1 recorrido.

Esto no debe interpretarse automáticamente como dominio del idioma.

## 2.3 Dominio o mastery

Representa lo que el usuario realmente sabe hacer.

Por ejemplo:

- present simple: 82 %;
- past simple: 44 %;
- pedir comida: 91 %;
- entender horas y horarios: 76 %;
- pronunciación de /θ/: 38 %;
- contar una experiencia pasada: 57 %.

Este valor debe calcularse a partir de múltiples evidencias.

## 2.4 Gamificación

Representa motivación y constancia:

- XP;
- niveles;
- rachas;
- medallas;
- objetivos diarios;
- logros.

La gamificación puede crecer indefinidamente y no debería confundirse con CEFR.

---

# 3. Evolucionar de un curso lineal a un Skill Graph

La mejora estructural más importante es añadir una red interna de habilidades.

La interfaz puede continuar mostrando:

> Curso → Unidad → Lección

pero cada actividad debe estar conectada internamente con uno o varios `skillIDs`.

## 3.1 Tipos de skills

Se recomienda utilizar al menos las siguientes categorías.

### Gramática

Ejemplos:

- `grammar:verb-be`
- `grammar:present-simple`
- `grammar:present-continuous`
- `grammar:past-simple`
- `grammar:past-irregular`
- `grammar:present-perfect`
- `grammar:first-conditional`

### Vocabulario

Ejemplos:

- `vocab:introductions`
- `vocab:family`
- `vocab:food`
- `vocab:travel`
- `vocab:work`
- `vocab:health`

### Funciones comunicativas

Ejemplos:

- `function:introduce-yourself`
- `function:ask-for-directions`
- `function:order-food`
- `function:make-a-complaint`
- `function:express-opinion`
- `function:tell-a-story`

### Speaking

Ejemplos:

- `speaking:short-answer`
- `speaking:describe-routine`
- `speaking:tell-past-story`
- `speaking:justify-opinion`
- `speaking:negotiate`

### Listening

Ejemplos:

- `listening:gist`
- `listening:detail`
- `listening:inference`
- `listening:number-time`
- `listening:connected-speech`

### Reading

Ejemplos:

- `reading:gist`
- `reading:detail`
- `reading:scan`
- `reading:inference`

### Writing

Ejemplos:

- `writing:sentence`
- `writing:message`
- `writing:email`
- `writing:story`
- `writing:argument`

### Pronunciación

Ejemplos:

- `pron:th`
- `pron:r`
- `pron:short-long-vowels`
- `pron:word-stress`
- `pron:sentence-stress`
- `pron:third-person-s`
- `pron:past-ed`
- `pron:connected-speech`

---

# 4. Añadir metadatos pedagógicos a cada lección

Cada lección debería declarar explícitamente qué enseña y qué evidencia puede generar.

Ejemplo conceptual:

```json
{
  "id": "en-a1-u3-present-simple",
  "cefr": "A1",
  "band": "A1.2",
  "goal": "Talk about routines and facts.",
  "canDo": [
    "talk-about-daily-routines",
    "ask-about-habits"
  ],
  "skills": [
    "grammar:present-simple",
    "function:ask-about-routines",
    "vocab:daily-routine",
    "speaking:short-personal-answer"
  ],
  "prerequisites": [
    "grammar:subject-pronouns"
  ],
  "pronunciationTargets": [
    "pron:third-person-s"
  ]
}
```

## 4.1 Ventajas

Esto permitirá:

- crear repasos personalizados;
- identificar lagunas concretas;
- adaptar Talk;
- adaptar Recall;
- crear checkpoints reales;
- generar métricas por habilidad;
- recomendar la siguiente lección;
- detectar si una actividad es demasiado difícil;
- reutilizar competencias entre distintos cursos.

---

# 5. Crear una matriz universal de "Can Do"

No se recomienda construir los demás idiomas copiando y traduciendo el curso de inglés.

La arquitectura debería definir competencias universales.

Ejemplos:

- presentarse;
- describir la familia;
- pedir comida;
- preguntar direcciones;
- hablar de rutinas;
- contar una experiencia pasada;
- expresar una opinión;
- resolver un problema;
- negociar;
- hacer una presentación.

Cada idioma implementará esa competencia con su propia gramática, vocabulario, pronunciación y convenciones culturales.

## 5.1 Ejemplo

Competencia universal:

```text
canDo: order-food
```

Inglés podría necesitar:

- `I'd like...`
- `Can I have...?`
- countable/uncountable nouns.

Francés podría requerir:

- fórmulas de cortesía específicas;
- artículos partitivos;
- pronunciación concreta.

Alemán podría utilizar:

- construcciones diferentes;
- orden sintáctico distinto;
- casos.

La competencia es común.

La implementación pedagógica es propia de cada idioma.

---

# 6. Nueva estructura de niveles

El curso debería crecer hasta soportar una ruta completa.

## 6.1 Starter / A0

Debe existir un bloque anterior a A1 para usuarios sin conocimientos.

Objetivos:

- primeras palabras;
- sonidos básicos;
- saludos;
- números;
- países;
- nombres;
- frases esenciales;
- entender instrucciones básicas;
- sobrevivir durante las primeras conversaciones.

Frases importantes:

- I don't understand.
- Can you repeat that?
- Slower, please.
- What does ... mean?
- How do you say ...?
- I speak a little English.

Este nivel debería generar una primera sensación de éxito oral muy rápido.

---

# 7. Ampliar A1

El A1 actual debe considerarse una primera parte de A1, no el nivel completo.

Un A1 completo debería cubrir de forma sistemática:

- verb `be`;
- subject pronouns;
- articles;
- plurals;
- possessives;
- demonstratives;
- `have / have got`;
- present simple;
- questions with `do`;
- third-person `-s`;
- adverbs of frequency;
- time;
- days and dates;
- `can / can't`;
- imperatives;
- `there is / there are`;
- prepositions;
- basic quantities;
- some/any;
- basic present continuous;
- basic requests;
- personal information;
- family;
- home;
- food;
- daily routine;
- town;
- directions;
- shopping;
- transport;
- hobbies;
- weather;
- basic health;
- common social interactions.

Objetivo orientativo:

- entre 30 y 40 lecciones core;
- varias unidades;
- checkpoints intermedios;
- suficiente speaking y listening para demostrar dominio funcional.

---

# 8. Ampliar A2

El A2 actual es todavía una introducción.

Un A2 completo debería incorporar, entre otros:

- past simple regular;
- past simple irregular;
- `was / were`;
- present continuous;
- future with `going to`;
- future plans;
- comparatives;
- superlatives;
- object pronouns;
- modal verbs básicos;
- `should`;
- `must / have to`;
- gerunds e infinitives básicos;
- countable/uncountable;
- quantifiers;
- connectors;
- everyday problem solving;
- travel;
- accommodation;
- health;
- work;
- social situations;
- past experiences.

Especialmente importante:

Los listenings no deberían utilizar sistemáticamente estructuras que el usuario todavía no ha estudiado sin marcar esas estructuras como exposición pasiva.

---

# 9. Crear B1

B1 debería cambiar el foco desde "construir frases" hacia "mantener conversaciones".

El usuario debería aprender a:

- contar historias;
- describir experiencias;
- explicar problemas;
- mantener conversaciones de varios minutos;
- expresar opiniones;
- justificar decisiones;
- hacer sugerencias;
- comparar alternativas;
- hablar de objetivos;
- reaccionar a situaciones inesperadas.

Contenido aproximado:

- present perfect;
- present perfect vs past simple;
- past continuous;
- future forms;
- first conditional;
- second conditional introductorio;
- relative clauses;
- `used to`;
- passive introductorio;
- reported speech básico;
- connectors;
- phrasal verbs frecuentes;
- collocations frecuentes.

---

# 10. Crear B2

A partir de B2, la aplicación debe parecer menos un curso tradicional y más entrenamiento lingüístico.

Debe incluir:

- debates;
- negociación;
- conversaciones largas;
- argumentos;
- opiniones con matices;
- desacuerdos educados;
- noticias;
- podcasts;
- situaciones laborales;
- diferentes registros;
- variedades de acento;
- lenguaje figurado básico;
- phrasal verbs;
- collocations;
- comprensión inferencial;
- speech natural.

---

# 11. Crear C1

C1 debería centrarse en:

- precisión;
- espontaneidad;
- matiz;
- estilo;
- registro;
- naturalidad;
- comprensión avanzada;
- argumentación;
- lenguaje académico;
- lenguaje profesional;
- expresiones idiomáticas;
- collocations avanzadas;
- adaptación al contexto.

El usuario debe ser capaz de expresar la misma idea de diferentes maneras dependiendo de:

- formalidad;
- audiencia;
- intención;
- contexto.

---

# 12. Tracks profesionales

El dominio profesional debería separarse del CEFR general.

Posibles tracks:

## English for Work

- reuniones;
- emails;
- llamadas;
- videollamadas;
- planificación;
- presentaciones;
- feedback;
- negociación.

## Job Interviews

- presentarse profesionalmente;
- describir experiencia;
- explicar proyectos;
- responder preguntas difíciles;
- negociar condiciones.

## Business

- ventas;
- negociación;
- clientes;
- propuestas;
- estrategia;
- reporting.

## Technology

- tickets;
- bugs;
- requisitos;
- arquitectura;
- demos;
- code reviews;
- reuniones técnicas;
- comunicación con producto.

## Hospitality

- recepción;
- reservas;
- atención al cliente;
- incidencias.

## Healthcare

- consultas;
- síntomas;
- instrucciones;
- vocabulario profesional.

---

# 13. Rediseñar la estructura de una unidad

Cada unidad debería comportarse como una pequeña misión.

Una unidad puede contener:

1. Learn
2. Drill
3. Listen
4. Pronounce
5. Talk
6. Read
7. Recall
8. Mission
9. Checkpoint

No todas estas actividades tienen que aparecer obligatoriamente en cada unidad.

El motor puede seleccionar lo que necesita el usuario.

---

# 14. Evolucionar las lecciones

La estructura actual de explicación + ejemplos + expresiones + práctica oral es buena y debería mantenerse.

Sin embargo, una lección debe generar más evidencia.

## 14.1 Nueva composición sugerida

### Introducción

Explicación breve del objetivo.

### Ejemplos

Entre 4 y 8 ejemplos claros.

### Chunks útiles

Frases completas que el usuario pueda reutilizar.

### Microejercicios

Reconocimiento y producción.

### Speaking guiado

Uso controlado de la estructura.

### Aplicación libre

Una pregunta o situación donde el alumno tenga que construir la respuesta.

### Mini checkpoint

Comprobar si la habilidad está disponible sin demasiadas ayudas.

---

# 15. Sustituir los cuatro ejercicios fijos por un Item Bank

Actualmente repetir siempre:

- choice;
- gap;
- order;
- written;

hace que la experiencia sea demasiado predecible.

Cada skill debería disponer de un banco de actividades.

Objetivo recomendado:

- 12–30 items posibles por skill;
- seleccionar 5–8 según historial y dificultad.

## 15.1 Tipos de ejercicio

Añadir:

- multiple choice;
- gap;
- sentence ordering;
- translation;
- dictation;
- listen and choose;
- listen and type;
- error correction;
- sentence transformation;
- matching;
- short spoken response;
- pronunciation;
- shadowing;
- dialogue completion;
- image description;
- reading comprehension;
- paraphrasing;
- free response.

---

# 16. Dificultad adaptativa

La dificultad no debería depender únicamente del nivel de la unidad.

La misma habilidad puede practicarse con varios grados de ayuda.

Ejemplo de progresión:

### Nivel 1

Elegir entre cuatro respuestas.

### Nivel 2

Completar una palabra.

### Nivel 3

Ordenar la frase.

### Nivel 4

Producir la frase desde cero.

### Nivel 5

Usarla oralmente.

### Nivel 6

Usarla espontáneamente dentro de una conversación.

Esta progresión genera evidencia mucho más útil sobre dominio real.

---

# 17. Rediseñar Listening

Listening debería convertirse en una habilidad de primera clase.

## 17.1 Metadatos recomendados

```json
{
  "difficulty": {
    "cefr": "B1",
    "wordCount": 160,
    "speechRate": "natural",
    "speakers": 2,
    "accent": "uk-north",
    "backgroundNoise": "light"
  },
  "skills": [
    "listening:gist",
    "listening:detail",
    "listening:inference"
  ]
}
```

## 17.2 Progresión de listening

### A1

- una voz;
- ritmo lento;
- frases claras;
- vocabulario frecuente;
- historias cortas.

### A2

- velocidad más natural;
- contracciones;
- dos personas;
- pequeñas distracciones.

### B1

- conversaciones reales;
- diferentes voces;
- información secundaria;
- algunas inferencias.

### B2

- velocidad natural;
- interrupciones;
- opiniones;
- lenguaje menos explícito;
- distintos acentos.

### C1

- audio prácticamente natural;
- podcasts;
- entrevistas;
- debates;
- ironía;
- matices;
- lenguaje indirecto.

---

# 18. Mejorar las preguntas de Listening

No deben limitarse a recuperar información literal.

Se recomienda combinar:

- gist;
- detail;
- sequence;
- speaker intention;
- inference;
- vocabulary in context;
- attitude;
- prediction;
- identifying key information.

Ejemplos:

- ¿Cuál es la idea principal?
- ¿Por qué crees que la persona tomó esa decisión?
- ¿Qué ocurrirá probablemente después?
- ¿Cómo se siente el hablante?
- ¿Qué palabra podría sustituir a esta expresión?
- ¿Qué información no es relevante?

---

# 19. Añadir pronunciación como módulo real

Actualmente pronunciación debería tener mucho más peso.

Se recomienda definir skills específicos.

Ejemplos para inglés:

- /θ/ y /ð/;
- short vs long vowels;
- final consonants;
- `-s`;
- `-ed`;
- word stress;
- sentence stress;
- reductions;
- linking;
- connected speech;
- intonation.

## 19.1 Tipos de actividad

- escuchar y repetir;
- minimal pairs;
- shadowing;
- grabar frase;
- comparar;
- detectar sonido;
- repetir dentro de contexto;
- practicar frase completa.

## 19.2 Integración con Talk

Talk debería registrar señales de pronunciación y fluidez cuando sea técnicamente viable.

---

# 20. Añadir Reading como skill independiente

Reading no debería ser simplemente texto de apoyo.

Debe tener contenidos propios.

## A1

- señales;
- mensajes;
- menús;
- horarios;
- conversaciones cortas.

## A2

- emails;
- anuncios;
- pequeñas historias;
- instrucciones.

## B1

- artículos cortos;
- reviews;
- posts;
- narraciones.

## B2

- prensa;
- ensayos breves;
- textos profesionales.

## C1

- artículos largos;
- textos académicos;
- análisis;
- documentación profesional.

---

# 21. Añadir Writing como skill independiente

El usuario debería pasar progresivamente de producir una frase a escribir textos.

## A1

- frases;
- formularios;
- mensajes cortos.

## A2

- mensajes;
- descripciones;
- emails sencillos.

## B1

- historias;
- opiniones;
- emails completos.

## B2

- argumentos;
- reports;
- propuestas;
- textos profesionales.

## C1

- textos complejos;
- síntesis;
- argumentación;
- documentos formales.

---

# 22. Evolucionar vocabulario y Recall

La repetición espaciada actual es una buena base.

Debe ampliarse para tratar vocabulario como una red.

Cada palabra o expresión debería guardar:

- forma;
- significado;
- ejemplo;
- pronunciación;
- tags;
- CEFR;
- skill;
- contexto de origen;
- historial de recall;
- errores;
- última aparición;
- próxima revisión;
- dominio.

## 22.1 No memorizar únicamente palabras

También deben recordarse:

- chunks;
- collocations;
- phrasal verbs;
- expresiones completas;
- patrones gramaticales.

Ejemplo:

En vez de memorizar únicamente:

> `decision`

memorizar:

> make a decision

---

# 23. El Tutor IA debe convertirse en el centro de aplicación práctica

Talk es una de las piezas con mayor potencial.

No debería ser un chatbot genérico.

Debe conocer:

- nivel del usuario;
- skills estudiados;
- vocabulario conocido;
- errores frecuentes;
- unidades completadas;
- topics favoritos;
- objetivos del usuario.

---

# 24. Conversaciones CEFR-aware

El tutor debe adaptar automáticamente:

- vocabulario;
- velocidad;
- longitud de respuesta;
- complejidad gramatical;
- cantidad de preguntas;
- nivel de ayuda.

Un A1 no debería recibir la misma conversación que un B2.

---

# 25. Conversaciones orientadas a objetivos

Cada conversación debe tener:

- escenario;
- objetivo;
- skills objetivo;
- vocabulario recomendado;
- dificultad;
- condiciones de éxito.

Ejemplo:

```json
{
  "scenario": "restaurant",
  "goal": "Order a meal and ask for the bill.",
  "skills": [
    "function:order-food",
    "grammar:some-any",
    "speaking:polite-request"
  ]
}
```

---

# 26. El Tutor IA debe registrar errores

Cada error importante debería poder convertirse en una señal de aprendizaje.

Ejemplo:

Usuario:

> Yesterday I go to the cinema.

Evento:

```text
skill: grammar:past-irregular-go
error: use_present_in_past_context
```

El tutor puede recastear:

> Oh, you went to the cinema?

Posteriormente el sistema puede volver a practicar `went`.

---

# 27. Bucle de aprendizaje basado en errores

Este debería ser uno de los motores principales del producto.

Flujo:

1. Usuario comete error.
2. Tutor lo detecta.
3. Tutor corrige sin romper la conversación.
4. El sistema guarda el patrón.
5. Recall lo vuelve a mostrar.
6. Una actividad posterior lo vuelve a practicar.
7. Talk comprueba si el usuario ya lo utiliza espontáneamente.

Este ciclo permite convertir conversaciones reales en material personalizado.

---

# 28. No corregir todo

El tutor no debe interrumpir cada frase.

Debe priorizar errores según:

- objetivo actual;
- frecuencia;
- gravedad;
- nivel;
- impacto comunicativo.

Tipos de errores:

### Critical

Impiden entender el mensaje.

### Target

Relacionados con el skill que se está aprendiendo.

### Repeated

Errores que el usuario repite.

### Minor

Errores pequeños que pueden ignorarse temporalmente.

---

# 29. Diferenciar exposición y dominio

Una actividad puede contener estructuras superiores al nivel actual.

Esto es positivo si está controlado.

El sistema debe diferenciar:

- target skill;
- known skill;
- exposure skill.

Ejemplo:

Un listening A2 puede contener present perfect.

Si el usuario todavía no lo ha estudiado:

```json
"exposureSkills": [
  "grammar:present-perfect"
]
```

No debería penalizarse al usuario por no dominarlo todavía.

---

# 30. Crear un Mastery Engine

El dominio de un skill debería depender de múltiples evidencias.

Posibles señales:

- ejercicio choice;
- ejercicio written;
- listening;
- speaking guiado;
- speaking espontáneo;
- recall;
- checkpoint;
- tiempo desde última práctica.

Cada evidencia puede tener distinto peso.

Ejemplo conceptual:

```text
multiple choice      = evidencia baja
gap                  = evidencia media
written              = evidencia media-alta
guided speaking      = evidencia alta
spontaneous speaking = evidencia muy alta
```

---

# 31. Añadir decaimiento temporal

Dominar algo hace dos meses no significa dominarlo hoy.

El mastery debería poder disminuir ligeramente con el tiempo si una habilidad no se utiliza.

Recall puede recuperar el dominio.

---

# 32. Sustituir el nivel finito 0–10 como medida lingüística

Los niveles 0–10 pueden mantenerse como gamificación.

No deberían representar capacidad real.

Se recomienda mostrar tres métricas:

## XP Level

Motivación.

## Course Progress

Contenido recorrido.

## Language Mastery

Capacidad estimada.

Ejemplo:

```text
Level 18
A1 course: 74 %
A1 readiness: 63 %
```

---

# 33. Crear CEFR Readiness

No afirmar automáticamente:

> Eres A2.

Mejor:

> A2 readiness: 71 %

o:

> Preparado para checkpoint A2.

Para completar un nivel deberían existir mínimos por skill.

Ejemplo:

```text
Grammar       >= 70
Vocabulary    >= 70
Listening     >= 65
Speaking      >= 65
Reading       >= 60
Writing       >= 60
Checkpoint    passed
```

---

# 34. Checkpoints reales

Cada nivel debe incluir evaluaciones integradas.

No únicamente más ejercicios.

Un checkpoint debería combinar:

- listening;
- reading;
- grammar;
- vocabulary;
- speaking;
- writing.

Idealmente debe existir:

- checkpoint por bloque;
- checkpoint final del nivel.

---

# 35. Rediseñar el Ledger

El ledger actual puede evolucionar desde una colección de completions hacia una colección de evidencias.

Ejemplo:

```json
{
  "activityID": "en-a2-u4-talk-restaurant",
  "skillIDs": [
    "function:order-food",
    "speaking:polite-request"
  ],
  "score": 0.82,
  "timestamp": "2026-09-29T10:00:00Z",
  "attempt": 3,
  "hintsUsed": 1,
  "duration": 94
}
```

---

# 36. Evitar depender indefinidamente de un ledger limitado

Cuando el catálogo aumente, 2.000 eventos pueden resultar insuficientes.

Modelo recomendado:

### Cliente

- snapshot de mastery;
- eventos recientes;
- cola de sync.

### Servidor

- historial completo o agregado;
- snapshots;
- analítica.

---

# 37. Snapshot de Mastery

En lugar de recalcular toda la historia constantemente, guardar agregados.

Ejemplo:

```json
{
  "skillID": "grammar:present-simple",
  "mastery": 0.84,
  "lastPracticed": "...",
  "attempts": 34,
  "correct": 28,
  "spontaneousSuccess": 5
}
```

El evento sigue siendo la fuente histórica.

El snapshot sirve para rendimiento.

---

# 38. Daily Learning Plan

La home debería poder construir una sesión diaria automáticamente.

Ejemplo:

```text
Tu entrenamiento de hoy · 12 min

3 min · Recall
4 min · Learn
5 min · Talk
```

La composición debe ser personalizada.

## 38.1 Factores

El motor puede decidir según:

- skills débiles;
- contenido nuevo;
- vocabulario próximo a olvidarse;
- errores recientes;
- tiempo disponible;
- nivel;
- actividad previa.

---

# 39. Sesiones cortas y objetivos claros

El usuario debería saber cuánto tardará.

Opciones:

- 5 min;
- 10 min;
- 15 min;
- 20 min.

El sistema crea una sesión dentro de ese presupuesto.

---

# 40. Continuidad entre sesiones

El aprendizaje de hoy debe influir en el de mañana.

Ejemplo:

Día 1:

- aprender past simple.

Día 2:

- recall de verbos;
- mini listening;
- conversación de fin de semana.

Día 4:

- nueva conversación donde aparezcan los mismos verbos.

---

# 41. Perfil de aprendizaje

Cada usuario podría tener un perfil interno.

Ejemplo:

```text
Speaking       78
Listening      64
Vocabulary     71
Grammar        81
Pronunciation  53
Reading        76
Writing        49
```

Esto permite adaptar automáticamente el plan.

---

# 42. Mostrar progreso útil

El progreso debería mostrar aprendizajes reales.

Ejemplo:

```text
Esta semana

+17 palabras consolidadas
+3 estructuras dominadas
42 min hablando
Listening +8 %
Pronunciación /θ/: necesita práctica
Past simple: sólido
```

Más útil que únicamente mostrar puntos acumulados.

---

# 43. Misiones

Una misión mezcla varias habilidades.

Ejemplos:

### A1

> Llegas a un hotel y necesitas hacer check-in.

### A2

> Has perdido una maleta y tienes que explicarlo.

### B1

> Tu vuelo fue cancelado. Busca una alternativa.

### B2

> Negocia una solución con un cliente.

Las misiones deben tener objetivos claros, no únicamente "hablar con IA".

---

# 44. Scenarios dinámicos

Las conversaciones pueden variar aunque la habilidad sea la misma.

Ejemplo:

`ask-for-directions`

Posibles contextos:

- estación;
- aeropuerto;
- centro histórico;
- universidad;
- hospital.

Esto evita repetir exactamente el mismo diálogo.

---

# 45. Contenido cultural

Cada idioma debería incorporar progresivamente:

- convenciones sociales;
- expresiones naturales;
- formalidad;
- tratamientos;
- variedades regionales;
- contexto cultural.

No debería enseñarse únicamente gramática traducida.

---

# 46. Variantes del idioma

Cuando el producto esté preparado, contemplar variantes.

Ejemplos:

### Inglés

- US;
- UK;
- international.

### Español

- Spain;
- LATAM general;
- variantes regionales cuando sea relevante.

No es necesario duplicar todo el curso.

Pueden compartirse skills y marcar diferencias de vocabulario o pronunciación.

---

# 47. Control de calidad del contenido

El crecimiento del catálogo hará imprescindible automatizar validaciones.

Crear scripts para comprobar:

- IDs únicos;
- skillIDs válidos;
- prerequisites válidos;
- referencias a unidades existentes;
- nivel CEFR;
- listenings asociados;
- respuestas vacías;
- opciones duplicadas;
- exercises con respuesta imposible;
- incoherencias entre prompt y answer.

---

# 48. Tests semánticos

Además de JSON válido, conviene crear tests de contenido.

Ejemplos:

- una respuesta no debe repetir exactamente el objeto buscado si el prompt exige otro;
- no enseñar estructuras posteriores como target antes de sus prerequisites;
- ninguna lección puede marcar un skill inexistente;
- todos los skills obligatorios de A1 deben estar cubiertos;
- las unidades deben cumplir un mínimo de variedad.

---

# 49. Revisar errores actuales de contenido

Antes de escalar el catálogo, corregir inconsistencias existentes.

Ejemplos detectados durante la revisión:

- ejercicios donde el pronombre del prompt no coincide con la respuesta;
- ejercicios de direcciones donde una entidad termina situada "next to" sí misma;
- correcciones que tratan como errónea una construcción inglesa válida;
- listenings A2 que introducen estructuras considerablemente posteriores al contenido enseñado.

El sistema de validación debe intentar detectar este tipo de errores antes de exportar.

---

# 50. Introducir Content Schema Versioning

Los JSON crecerán.

Añadir una versión explícita.

Ejemplo:

```json
{
  "schemaVersion": 2
}
```

Esto permitirá migrar contenido sin romper clientes antiguos.

---

# 51. Separar entidades

A largo plazo puede resultar útil separar:

- courses;
- units;
- lessons;
- skills;
- vocab;
- listening;
- reading;
- conversation scenarios;
- pronunciation;
- assessments.

No es obligatorio hacerlo inmediatamente.

Pero el modelo debe permitirlo.

---

# 52. Mantener IDs estables

Los IDs actuales deben conservarse cuando sea posible.

Nunca modificar un ID porque cambie el texto visible.

Esto protege:

- ledger;
- sync;
- analytics;
- historial;
- achievements;
- mastery.

---

# 53. Añadir Content IDs y Versions

Si una actividad cambia significativamente, puede ser útil diferenciar:

```text
itemID: en-listen-language-cafe
version: 2
```

Así el sistema sabe si el usuario completó una versión anterior.

---

# 54. Motor de recomendaciones

Con mastery + skill graph puede existir un recomendador.

Ejemplo:

> Recommended next: Present continuous

Motivo:

- present simple dominado;
- vocabulary routine suficiente;
- prerequisite completado.

También puede recomendar:

> Review past simple before continuing.

---

# 55. Prerequisites

Las skills deberían poder declarar dependencias.

Ejemplo:

```text
past-simple
  ↓
past-continuous
  ↓
past-perfect
```

Esto evita saltos pedagógicos.

---

# 56. Knowledge Graph pedagógico

A medida que crezca la aplicación, las dependencias pueden formar un grafo.

Ejemplo:

```text
verb-be
 ├── present-continuous
 ├── there-is
 └── passive

present-simple
 ├── frequency
 ├── first-conditional
 └── present-perfect comparison
```

El recomendador puede utilizar esta estructura.

---

# 57. Objetivos de vocabulario por nivel

Cada nivel debe establecer un rango aproximado de vocabulario activo.

No basta con añadir palabras sin control.

Cada palabra debería tener:

- CEFR;
- frecuencia;
- tema;
- prioridad;
- skill.

El curso debe controlar que no dependa de demasiado vocabulario desconocido.

---

# 58. Chunks antes que palabras aisladas

Priorizar expresiones útiles.

Ejemplos:

- Nice to meet you.
- I'd like...
- It depends.
- I'm not sure.
- That makes sense.
- As far as I know...
- On the other hand...

A niveles superiores, la naturalidad depende mucho de estas unidades.

---

# 59. Fluency Score

En speaking puede existir una métrica separada basada en:

- duración de respuestas;
- pausas;
- dependencia de hints;
- autocorrecciones;
- vocabulario;
- variedad gramatical.

No debe mezclarse completamente con corrección gramatical.

---

# 60. Accuracy Score

Medir por separado:

- gramática;
- morfología;
- vocabulario;
- errores repetidos.

Un usuario puede tener mucha fluidez y poca precisión o al revés.

---

# 61. Listening Score

Puede tener subcomponentes:

- gist;
- detail;
- numbers;
- inference;
- natural speech.

---

# 62. Pronunciation Score

Puede dividirse en:

- sounds;
- stress;
- rhythm;
- intonation;
- connected speech.

---

# 63. Writing Score

En niveles superiores:

- grammar;
- vocabulary;
- coherence;
- structure;
- register;
- task completion.

---

# 64. Sistema de hints

Registrar cuánta ayuda necesita el usuario.

Ejemplo:

1. sin pista;
2. primera palabra;
3. vocabulario;
4. estructura;
5. solución.

Una respuesta correcta después de cuatro pistas no debería aportar el mismo mastery que una respuesta espontánea.

---

# 65. Reintentos inteligentes

No repetir inmediatamente el mismo ejercicio.

Si falla:

1. explicar;
2. mostrar otro ejemplo;
3. presentar una variante;
4. volver a probar unos minutos después.

Esto reduce memorización superficial.

---

# 66. Interleaving

No practicar veinte veces el mismo concepto seguido.

Combinar habilidades.

Ejemplo:

- present simple;
- vocabulary;
- past simple;
- present simple de nuevo.

Esto mejora recuperación real.

---

# 67. Spaced Repetition para gramática

La repetición espaciada no debería limitarse a vocabulario.

También se pueden revisar:

- grammar patterns;
- pronunciation;
- chunks;
- errores;
- conversation skills.

---

# 68. Dynamic Recall Queue

La cola de recall debería combinar:

- vocabulario próximo a vencer;
- errores recientes;
- skills debilitados;
- nuevos chunks;
- pronunciación problemática.

---

# 69. Mastery por contexto

Una misma estructura puede dominarse en ejercicios pero fallar al hablar.

Podría mantenerse:

```text
present-simple:
  recognition: 95
  production: 74
  speaking: 61
```

Esto permite una personalización mucho más precisa.

---

# 70. Analytics pedagógica

Registrar métricas útiles para mejorar el curso:

- ejercicio con demasiados fallos;
- ejercicio demasiado fácil;
- lección con abandono alto;
- conversación demasiado difícil;
- listening con preguntas ambiguas;
- skill que genera errores repetidos;
- tiempo medio por actividad.

---

# 71. Content Health Dashboard

Crear herramientas internas para detectar:

- lessons con poco engagement;
- exercises ambiguos;
- content gaps;
- skills sin suficientes actividades;
- skills sin listening;
- skills sin speaking;
- niveles con distribución desequilibrada.

---

# 72. Herramientas internas de creación

El crecimiento manual de JSON puede convertirse en un cuello de botella.

A largo plazo conviene construir herramientas internas para:

- crear lecciones;
- validar schemas;
- previsualizar ejercicios;
- buscar IDs;
- asociar skills;
- crear listenings;
- revisar cobertura CEFR;
- detectar dependencias.

El JSON puede continuar siendo la fuente canónica.

---

# 73. Generación asistida por IA para contenido

La IA puede ayudar a generar borradores de:

- ejercicios;
- variaciones;
- ejemplos;
- distractores;
- listenings;
- scenarios.

Pero el contenido canónico debería pasar por:

- validadores;
- revisión humana;
- tests.

Nunca asumir que el contenido generado es correcto automáticamente.

---

# 74. Pipeline editorial

Flujo recomendado:

```text
Draft
↓
Automatic validation
↓
Linguistic review
↓
Pedagogical review
↓
Preview
↓
Publish
```

---

# 75. Roadmap recomendado

## Fase 1 — Fundamentos del motor

1. Crear catálogo de skills.
2. Añadir `skillIDs`.
3. Añadir `canDo`.
4. Añadir `prerequisites`.
5. Añadir `schemaVersion`.
6. Crear Mastery Engine básico.
7. Separar XP, progreso y mastery.

## Fase 2 — Completar inglés A1

1. Expandir de 12 lecciones A1 actuales a aproximadamente 30–40.
2. Añadir present continuous.
3. Añadir más vocabulario.
4. Añadir pronunciation.
5. Añadir reading.
6. Crear checkpoint A1.
7. Mejorar listenings.

## Fase 3 — Completar inglés A2

1. Añadir past irregular.
2. Añadir comparatives.
3. Añadir future.
4. Añadir modal verbs.
5. Añadir nuevas situaciones.
6. Crear checkpoint A2.

## Fase 4 — Personalización

1. Error memory.
2. Recall adaptativo.
3. Daily Plan.
4. Recomendador.
5. Talk basado en skills.
6. Mastery por habilidad.

## Fase 5 — B1

Construir el primer nivel intermedio completo.

Objetivo:

- conversación prolongada;
- storytelling;
- opinión;
- situaciones imprevistas.

## Fase 6 — Escalado multiidioma

Utilizar:

- Can Do Matrix;
- Skill Graph;
- schemas;
- herramientas editoriales.

Cada idioma implementa su ruta pedagógica propia.

## Fase 7 — B2, C1 y tracks profesionales

Una vez validado el motor de aprendizaje, ampliar profundidad.

---

# 76. Prioridades absolutas

Si hubiera que reducir todo el documento a diez mejoras prioritarias:

1. Crear un **Skill Graph**.
2. Separar **XP, Course Progress y Mastery**.
3. Convertir Talk en una fuente de **evidencia y errores**.
4. Crear un **Mastery Engine**.
5. Completar A1 y A2 antes de considerarlos terminados.
6. Introducir **pronunciación, reading y writing** como skills reales.
7. Sustituir cuatro ejercicios fijos por un **Item Bank**.
8. Crear **Recall adaptativo basado en errores y skills**.
9. Añadir **Daily Learning Plan**.
10. Construir cada idioma desde una **Can Do Matrix**, no mediante traducción directa.

---

# 77. Visión final del producto

La aplicación debería ser capaz de transformar:

```text
Contenido estructurado
+
Actividad del usuario
+
Tutor IA
+
Repetición espaciada
+
Skill Graph
```

en:

```text
Un plan de aprendizaje personalizado
```

El sistema debe saber:

- lo que el usuario ha estudiado;
- lo que sabe;
- lo que está olvidando;
- lo que todavía no domina;
- los errores que repite;
- la siguiente habilidad óptima;
- qué actividad ofrecer hoy.

El objetivo final no es crear una biblioteca enorme de lecciones.

El objetivo es crear un motor que utilice el contenido para producir aprendizaje medible, progresivo y personalizado desde nivel cero hasta uso avanzado y profesional del idioma.
