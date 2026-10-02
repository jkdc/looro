# Análisis UX/UI profundo — Pantalla **Aprender**

## Estado del documento

**Conclusión:** sí se recomiendan cambios.

El diseño actual tiene una base funcional sólida, pero la pantalla está intentando resolver demasiados objetivos simultáneamente:

- recordar tareas pendientes;
- construir una sesión diaria;
- mostrar gamificación;
- mostrar avance del curso;
- navegar por decenas de unidades;
- mostrar niveles CEFR;
- mostrar tracks profesionales;
- desbloquear checkpoints;
- sugerir la siguiente lección.

Esto funciona con un catálogo pequeño, pero empezará a generar carga cognitiva, scroll excesivo y confusión cuando el usuario tenga decenas de unidades, múltiples niveles CEFR y varios tracks.

La recomendación principal es convertir **Aprender** en una pantalla orientada a una sola pregunta:

> **¿Qué debería hacer ahora para seguir mejorando?**

El temario completo, el mastery detallado, la gamificación y los tracks siguen estando disponibles, pero con una jerarquía clara y progressive disclosure.

---

# 1. Alcance del análisis

Este análisis se basa en la especificación escrita de la pantalla y su wireframe textual.

Se puede evaluar con profundidad:

- arquitectura de información;
- jerarquía;
- flujos;
- navegación;
- carga cognitiva;
- escalabilidad;
- claridad de conceptos;
- lógica de estados;
- prioridades;
- copy;
- accesibilidad estructural;
- interacción;
- coherencia con mastery/CEFR;
- usabilidad del Daily Plan;
- descubribilidad.

No se dispone de una captura visual real, por lo que no se puede evaluar todavía con precisión:

- contraste real;
- colores;
- tipografía exacta;
- tamaño real de controles;
- densidad visual exacta;
- iconografía final;
- animaciones;
- elevaciones/sombras;
- calidad de implementación visual Android/iOS.

Las recomendaciones visuales de este documento son por tanto directrices, no una auditoría pixel-perfect.

---

# 2. Diagnóstico general

## Lo que ya está bien

El diseño contiene varias decisiones buenas que conviene conservar:

1. Existe un **Daily Plan**.
2. El usuario puede seleccionar cuánto tiempo tiene.
3. Existe una recomendación de siguiente lección.
4. El contenido se organiza por CEFR.
5. Gamificación y mastery están conceptualmente separados.
6. Existen checkpoints.
7. El temario conserva una estructura comprensible: curso → nivel → unidad → lección.
8. Recall está integrado en Aprender.
9. Los professional tracks están conceptualmente separados del curso base.
10. El sistema puede crecer mediante skills y prerequisites.

El problema no es que falten piezas. El problema es la **priorización de esas piezas en una pantalla móvil**.

---

# 3. Problema principal: demasiados objetivos visuales

La pantalla actual contiene, de arriba a abajo:

```text
Page heading
Recall
Daily Plan
Course
  Level
  Course progress
  CEFR sections
  Units
  Lessons
Professional track
Checkpoint
```

Cada bloque quiere atención.

Eso genera una pantalla donde prácticamente todo parece importante.

En UX, si todo es prioritario, nada es realmente prioritario.

La pantalla debe establecer tres niveles de jerarquía.

## Prioridad 1 — Acción inmediata

Responder:

> ¿Qué hago ahora?

Debe existir una única acción primaria.

## Prioridad 2 — Orientación

Responder:

> ¿Dónde estoy y cómo voy?

Debe ser visible, pero secundaria.

## Prioridad 3 — Exploración

Responder:

> ¿Qué más puedo estudiar?

Debe existir bajo demanda.

---

# 4. Nueva jerarquía recomendada

La pantalla debería organizarse así:

```text
APRENDER

┌──────────────────────────────────┐
│ Tu entrenamiento de hoy          │
│ 10 min · 3 pasos                  │
│                                  │
│ 1/3 Repaso                       │
│ 2/3 Siguiente habilidad          │
│ 3/3 Practicar hablando           │
│                                  │
│ [ Empezar entrenamiento ]        │
└──────────────────────────────────┘

Tu camino
A1 · 42% completado
Dominio estimado A1 · 36%
[ Continuar: Present continuous ]

┌──────────────────────────────────┐
│ First sentences          ✓       │
│ People & things          ✓       │
│ Everyday actions         2/3     │
│ Right now                Actual  │
└──────────────────────────────────┘

[ Ver temario completo ]

Tu progreso
Grammar 72 · Listening 61 · Speak 48
[ Ver habilidades ]

Especializaciones
[ English for Work → ]
```

Checkpoint, recall urgente y otros elementos se integran dinámicamente en estos bloques en lugar de competir como tarjetas independientes.

---

# 5. Acción primaria: Daily Plan

## Problema actual

El Daily Plan lista pasos, pero no existe en el wireframe una CTA principal inequívoca.

El usuario puede entender qué contiene la sesión, pero no necesariamente qué debe pulsar.

Además aparecen cuatro chips:

```text
5 min
10 min
15 min
20 min
```

y cada paso también tiene duración.

El resultado puede parecer más un configurador que un inicio rápido.

## Recomendación

El Daily Plan debe tener una CTA principal:

```text
[ Empezar 10 min ]
```

o, si ya comenzó:

```text
[ Continuar · 6 min restantes ]
```

El presupuesto temporal debe ser secundario.

Ejemplo:

```text
Tu entrenamiento de hoy       Cambiar: 10 min

Repaso                  3 min
Nueva habilidad         4 min
Hablar                   3 min

[ Empezar entrenamiento ]
```

---

# 6. Presupuesto de tiempo

Los cuatro presupuestos son útiles, pero no necesitan ocupar permanentemente la parte principal de la tarjeta.

## Primera visita

```text
¿Cuánto tiempo tienes hoy?

[5] [10] [15] [20 min]
```

## Después

Recordar la última preferencia.

La tarjeta muestra:

```text
10 min
Cambiar
```

El usuario no debería elegir su presupuesto todos los días si suele estudiar la misma cantidad.

---

# 7. El Daily Plan necesita estado de sesión

Debe existir una noción clara de:

```text
notStarted
inProgress
completed
```

## notStarted

```text
Tu entrenamiento de hoy
10 min · 3 pasos

[ Empezar ]
```

## inProgress

```text
Entrenamiento de hoy
1 de 3 completado · 6 min restantes

✓ Repaso
→ Present continuous
○ Talk: Right now

[ Continuar ]
```

## completed

```text
Entrenamiento completado ✓

10 min
3 actividades
+ skills practicados

[ Ver progreso ]
```

No volver a mostrar la misma tarjeta como si no hubiera sucedido nada.

---

# 8. No reordenar el Daily Plan mientras se realiza

El motor puede ser adaptativo, pero la sesión visible debe ser estable.

Mal comportamiento:

```text
usuario completa recall
↓
motor recalcula
↓
listening desaparece
↓
aparece una lección diferente
```

Esto provoca sensación de inconsistencia.

## Recomendación

Cuando se pulsa:

```text
Empezar entrenamiento
```

crear un **Session Plan Snapshot**.

Durante esa sesión:

```text
steps = stable
```

Al completar la sesión se puede recalcular el siguiente plan.

---

# 9. Problema: Recall está duplicado

Actualmente existe:

```text
Recall card
```

y además:

```text
Daily Plan
  → Repasa tus palabras
```

Son dos entradas a la misma necesidad.

Esto crea redundancia, dos CTAs competidoras y confusión sobre cuál debe hacerse primero.

## Recomendación

Recall debería integrarse normalmente dentro del Daily Plan.

Solo mostrar una alerta independiente si existe una situación excepcional.

---

# 10. Recall no debería hablar solo de palabras

La arquitectura futura contempla:

- vocabulario;
- chunks;
- errores;
- patrones gramaticales;
- pronunciación;
- skills debilitados.

Por tanto:

```text
12 palabras pendientes
```

envejecerá mal.

## Copy recomendado

```text
12 repasos pendientes
```

o:

```text
Repaso · 12 elementos
```

Al abrir:

```text
7 palabras
3 expresiones
2 errores recientes
```

---

# 11. No usar el backlog de Recall como elemento de culpa

Si el usuario vuelve después de un mes puede tener:

```text
247 elementos pendientes
```

Mostrar ese número prominentemente puede ser desmotivador.

## Mejor

```text
Repaso de hoy · 5 min
```

y dentro:

```text
Seleccionaremos los elementos más importantes.
```

El motor gestiona el backlog. El usuario gestiona su sesión.

---

# 12. Daily Plan actual: problema pedagógico

La lógica:

```text
Recall
→ Lesson
→ Listening/Reading
```

es demasiado rígida.

No incluye explícitamente:

- speaking;
- Talk;
- pronunciation;
- weakest skill;
- errores recientes.

Esto es especialmente problemático si Talk va a ser una pieza central de la experiencia.

## Nueva lógica conceptual

Cada Daily Plan debería intentar contener:

```text
RETRIEVE
+
LEARN / STRENGTHEN
+
APPLY
```

### Retrieve

- Recall;
- error review;
- spaced practice.

### Learn / Strengthen

- nueva lección;
- skill débil;
- pronunciation;
- listening;
- reading.

### Apply

Preferiblemente:

- Talk;
- spoken response;
- writing;
- mission.

---

# 13. Daily Plan según tiempo disponible

No se debe dividir siempre el tiempo con porcentajes rígidos.

## 5 minutos

Una única misión concentrada.

```text
2 min · Repaso
3 min · Talk corto
```

o incluso:

```text
5 min · Skill urgente
```

## 10 minutos

```text
2 min · Recall
4 min · Learn
4 min · Apply
```

## 15 minutos

```text
3 min · Recall
5 min · Learn
4 min · Listen
3 min · Talk
```

## 20 minutos

Puede combinar:

```text
Recall
Learn
Listen/Read
Talk
```

La unidad pedagógica importante no es el reparto matemático. Es una sesión con principio, objetivo y aplicación.

---

# 14. El plan debe explicar por qué recomienda algo

La personalización se vuelve mucho más creíble si el usuario entiende mínimamente la razón.

Ejemplos:

```text
Repasar past simple
Lo fallaste dos veces esta semana.
```

```text
Listening: At the station
Practica la habilidad que acabas de aprender.
```

```text
Talk: Your weekend
Buen momento para usar past simple sin ayudas.
```

No hace falta mostrar explicaciones técnicas de mastery. Basta una razón humana.

---

# 15. Problema de escalabilidad del Course Card

El curso de inglés ya contempla decenas de unidades y 77 lecciones.

Una tarjeta que expande A1, A2, B1, B2 y C1 puede convertirse en una lista excesivamente larga.

Y si cada unidad expone lecciones, la pantalla Aprender se convierte en todo el catálogo.

Esto perjudica:

- orientación;
- velocidad de escaneo;
- sensación de avance;
- navegación;
- localización de la unidad actual.

---

# 16. Progressive disclosure del temario

No mostrar todos los niveles expandidos.

## Comportamiento recomendado

```text
A1  actual     expandido
A2             colapsado
B1             colapsado
B2             colapsado
C1             colapsado
```

Cuando el usuario esté en A2:

```text
A1 ✓           colapsado
A2 actual      expandido
B1             colapsado
...
```

---

# 17. Niveles CEFR como navegación

Puede utilizarse un selector:

```text
[A1] [A2] [B1] [B2] [C1]
```

pero con cuidado por accesibilidad y escalado de texto.

Alternativa recomendada para móvil:

```text
A1 · Current
12 unidades
⌄

A2
6 unidades
›

B1
4 unidades
›
```

Esto escala mejor con Dynamic Type.

---

# 18. Auto-focus en la unidad actual

Cuando el usuario abre Aprender:

1. mostrar Daily Plan;
2. mostrar progreso;
3. dentro del temario, expandir automáticamente el nivel actual;
4. posicionar visualmente la unidad actual de forma fácil de encontrar.

No obligar al usuario a localizar manualmente su unidad entre 25 unidades.

---

# 19. "Suggested next" está duplicado conceptualmente

Existe:

- Daily Plan;
- `recommendedLesson()`;
- label `Suggested next` dentro de LessonRow.

No necesariamente es malo, pero debe existir una única representación dominante.

## Recomendación

El Daily Plan es la recomendación primaria.

Dentro del temario:

```text
Siguiente
```

puede aparecer como indicador contextual, no como otro CTA protagonista.

---

# 20. Course progress: demasiadas métricas

Actualmente pueden coexistir:

```text
Nivel 3
12 puntos
3 lecciones
0 listening
17 % completed
estrellas
A1/A2/B1...
```

Esto obliga al usuario a interpretar demasiados sistemas simultáneos.

Preguntas naturales:

- ¿Nivel 3 significa A1?
- ¿Los 12 puntos son importantes?
- ¿17 % de qué?
- ¿Las estrellas son mastery?
- ¿A1 mide otra cosa?
- ¿Qué tengo que mejorar?

La pantalla debe reducir estas preguntas.

---

# 21. Separar claramente tres sistemas

## Gamificación

```text
XP / Level
```

Motivación.

## Course Progress

```text
18/40 lessons
A1 · 45%
```

Contenido recorrido.

## Mastery

```text
A1 readiness · 38%
```

Capacidad estimada.

No deben parecer la misma métrica.

---

# 22. Problema grave: Level 0–10 ya no escala

El documento indica un máximo de puntos muy superior al último umbral de la escalera.

Si el máximo del curso crece por encima de 200 puntos pero el nivel 10 continúa en 78, el usuario puede alcanzar `Foundations champion` mucho antes de acabar el curso.

Esto destruye el significado de la gamificación.

## Recomendación fuerte

Eliminar la escalera finita 0–10 como progreso central del curso.

### Opción recomendada

XP global e ilimitado:

```text
Level 18
1,420 XP
```

No pretende representar completion.

### Alternativa

Recalibrar niveles dinámicamente.

Menos recomendable porque cualquier expansión futura del catálogo vuelve a modificar la escala.

---

# 23. Gamificación no debería dominar Learn

En Aprender interesa principalmente:

```text
qué estudiar
+
cómo voy
```

XP puede mostrarse de forma compacta o vivir en perfil/progreso.

No dedicar una LevelCard grande dentro de cada CourseCard.

---

# 24. "Foundations champion" puede inducir una afirmación incorrecta

Aunque explícitamente no sea CEFR, la palabra `champion` puede hacer pensar al usuario que ha terminado o dominado Foundations.

Si el curso sigue teniendo decenas de lecciones pendientes, el mensaje es incoherente.

Debe ligarse a gamificación general o reemplazarse por títulos que no impliquen completion.

---

# 25. Course Completion global puede ser desmotivador

Mostrar:

```text
Completed: 17%
```

sobre un curso A1→C1 gigantesco puede hacer sentir que el usuario nunca avanza.

Es más útil mostrar progreso contextual.

Ejemplo:

```text
A1
8 de 12 unidades
67%
```

y secundariamente:

```text
Curso completo · 17%
```

solo en detalle.

---

# 26. Unidad: no usar estrellas sin significado explícito

Una fila:

```text
★★ Everyday actions
```

puede interpretarse como puntuación, calidad, mastery, rating o completion.

## Recomendación

Usar estados más explícitos:

```text
✓ Completada
2/3 lecciones
En progreso
Repasar
Nueva
```

Opcionalmente, mastery en una vista detallada.

---

# 27. LessonRow: estado recomendado

Estados posibles:

```text
New
In progress
Ready to review
Completed
Mastered
```

No mostrar únicamente:

```text
Practised N×
```

porque el número de intentos no dice si el usuario sabe hacerlo.

`Practised 7×` puede incluso comunicar fracaso.

---

# 28. Unidad actual

La unidad actual debería tener una presentación distinta, pero no depender solo del color.

Ejemplo:

```text
RIGHT NOW
Actual

2 de 3 lecciones
███████░░

[ Continuar ]
```

---

# 29. Checkpoint: posición actual incorrecta

En el wireframe aparece después de todo el catálogo.

Con un curso largo, el usuario puede no llegar nunca visualmente hasta él.

Un checkpoint es un momento importante del aprendizaje.

Debe aparecer **dentro del nivel CEFR correspondiente**.

Ejemplo:

```text
A1

Unit 10
Unit 11
Unit 12

────────────
A1 Checkpoint
Comprueba lo que sabes
[ Empezar ]
────────────
```

---

# 30. Checkpoint visible antes de desbloquearse

No conviene que aparezca por sorpresa únicamente al final.

Puede existir como objetivo futuro:

```text
A1 Checkpoint
🔒 Completa 3 unidades más
```

Esto crea anticipación, explica la estructura y mejora orientación.

---

# 31. Checkpoint no debería desaparecer al aprobarse

Desaparecer elimina logro, historial, referencia y opción de repetir.

Después de aprobar:

```text
A1 Checkpoint ✓

Passed · 82%
20 Sep

[ Ver resultados ]
[ Repetir ]
```

Puede quedar colapsado.

---

# 32. "Todas las lecciones intentadas" no es buen criterio de readiness

Actualmente `lesson attempted ≥ 1` puede bastar para desbloquear el checkpoint.

Eso permite abrir todas las lecciones, hacer una práctica mínima y desbloquear assessment sin dominar el contenido.

## Separar dos conceptos

### Unlock

El usuario ha recorrido contenido suficiente.

### Recommended

El sistema considera que está preparado.

Ejemplo:

```text
Checkpoint disponible
Readiness: 64%

Recomendado a partir de 70%
```

El usuario podría intentarlo antes si se desea libertad.

---

# 33. Registrar los fallos de checkpoint como evidencia

Actualmente los fallos no se registran.

Esto provoca dos problemas:

1. se pierde información pedagógica muy valiosa;
2. mastery puede quedar sesgado porque solo los éxitos generan evidencia.

Recomendación:

```text
checkpoint attempt
```

siempre produce datos.

Pero solo un aprobado produce:

```text
checkpointPassed = true
```

Separar `assessment_attempt` y `assessment_pass`, o guardar score + passed.

---

# 34. Feedback después del checkpoint

No mostrar solamente:

```text
68% — failed
```

Mostrar:

```text
Casi lo tienes

Grammar        82 ✓
Vocabulary     75 ✓
Listening      58
Speaking       61

Te recomendamos:
• Listening: Everyday conversations
• Talk: Describe your week

[ Practicar puntos débiles ]
```

Esto convierte un fallo en siguiente acción.

---

# 35. Mastery está demasiado escondido

Actualmente vive en `Settings → Tu progreso → Habilidades`.

Settings es conceptualmente un lugar para configuración, no para una función central de aprendizaje.

El usuario puede no descubrir nunca su mapa de habilidades.

## Recomendación

Desde Aprender mostrar una entrada compacta:

```text
Tu progreso

Grammar       72
Listening     61
Speaking      48

[ Ver habilidades ]
```

La pantalla detallada puede seguir existiendo, pero debería ser accesible desde Aprender y/o Perfil/Progreso.

---

# 36. Settings no debe ser la única entrada a progreso

Mejor arquitectura:

```text
Aprender
→ resumen de progreso
→ Habilidades / Progreso detallado
```

Settings puede contener únicamente configuraciones.

---

# 37. Cómo presentar mastery sin crear ansiedad

Un porcentaje que disminuye por decay puede parecer:

> la app me está quitando progreso.

Debe explicarse.

En lugar de mostrar únicamente:

```text
Grammar 55%
```

puede utilizarse:

```text
Grammar
En progreso · 55%
```

y en detalle:

```text
Algunas habilidades necesitan repaso porque llevan tiempo sin practicarse.
```

---

# 38. Estados humanos de mastery

Se puede mapear internamente a rangos comprensibles.

Ejemplo conceptual:

```text
0–29   Por empezar
30–49  En desarrollo
50–69  En progreso
70–84  Sólido
85–100 Muy sólido
```

No utilizar estos rangos como certificaciones.

---

# 39. Categorías sin evidencia

Nunca mostrar:

```text
Writing 0%
```

si el usuario todavía no ha realizado ninguna actividad de writing.

Mostrar:

```text
Writing
Aún no evaluado
```

Esto evita transmitir incapacidad inexistente.

---

# 40. CEFR + mastery

Puede existir una visualización útil:

```text
A1

Course progress     78%
Readiness           64%
```

Pero requiere microcopy:

```text
Progreso
Contenido recorrido

Preparación
Lo que ya puedes demostrar
```

No presentar dos barras sin explicar la diferencia.

---

# 41. Professional tracks

Actualmente pueden aparecer inmediatamente debajo del curso principal.

Para un usuario A1, ver Work, Business, Technology o Healthcare puede introducir ruido demasiado pronto.

## Recomendación

Agruparlos en:

```text
Especializaciones
```

y mostrar solo cuando tengan relevancia.

Ejemplo:

```text
Especializaciones

English for Work
Nivel recomendado: B1
```

---

# 42. No bloquear necesariamente los tracks

Un usuario puede necesitar inglés profesional incluso con A2.

Mejor:

```text
Recommended B1
```

que:

```text
Locked until B1
```

salvo que pedagógicamente el contenido sea realmente imposible.

---

# 43. Ordenar tracks según objetivo del usuario

Si durante onboarding el usuario selecciona `Trabajo`, mostrar primero English for Work.

Si selecciona `Viajar`, no ocupar espacio principal con Business.

---

# 44. Card nesting

El wireframe sugiere múltiples niveles de tarjetas y bordes:

```text
Course Card
  Level Card
  A1 container
    Unit rows
  A2 container
  Professional card
```

En móvil esto puede producir card dentro de card dentro de card.

Visualmente genera ruido y reduce espacio horizontal.

## Recomendación

Usar superficie plana para el temario.

Ejemplo:

```text
TU CAMINO

A1 · Current
─────────────────
✓ First sentences
✓ People & things
→ Everyday actions
  Right now
─────────────────

A2
6 unidades
```

Reservar tarjetas elevadas para módulos accionables:

- Daily Plan;
- checkpoint;
- alertas importantes.

---

# 45. Regla visual sugerida

### Cards

Solo para acciones u objetos independientes.

### Sections

Para organización jerárquica.

No convertir cada nivel y unidad en una tarjeta.

---

# 46. Iconografía

El diseño textual mezcla símbolos y emojis.

En implementación final no conviene depender de emojis porque cambian entre plataformas, tienen estilos inconsistentes y pueden tener significados ambiguos.

## Recomendación

Usar un sistema consistente:

### Android

Material Symbols.

### iOS

SF Symbols.

Mantener equivalencias semánticas.

---

# 47. Recall icon

`♜` no comunica claramente repetición espaciada.

Mejor conceptos como:

- refresh;
- history;
- brain;
- repeat;
- cards.

Siempre acompañados de texto.

---

# 48. Accent color

Aunque no conocemos la paleta actual, el color de máxima atención debería reservarse para:

```text
Empezar
Continuar
Checkpoint ready
```

No utilizar el mismo tratamiento destacado para XP, CEFR, recall, tracks, unidades y mastery simultáneamente.

---

# 49. Tipografía y jerarquía

Jerarquía conceptual recomendada:

```text
Page title
Section title
Primary card title
Item title
Metadata
Helper text
```

Evitar demasiados tamaños y pesos.

Ejemplo:

```text
Aprender                    Page title
Tu entrenamiento de hoy     Primary title
Present continuous          Item title
4 min · Grammar             Metadata
```

---

# 50. All caps

`APRENDER` puede funcionar como eyebrow, pero si el nombre de la pantalla ya es Aprender no es necesario duplicarlo.

Puede simplificarse a:

```text
Aprender
```

si la navegación ya aporta contexto suficiente.

---

# 51. Copy orientado a acción

Preferir:

```text
Continuar
Empezar
Repasar
Practicar
Ver habilidades
```

sobre etiquetas abstractas.

`Suggested next` en UI española debería convertirse en `Siguiente` o `Recomendado`.

---

# 52. Idioma del interfaz vs idioma objetivo

La pantalla mezcla nombres potencialmente en inglés con interfaz posiblemente española.

Debe existir una regla clara.

## Recomendación

### Navegación y metadata

Idioma de interfaz.

### Contenido que el usuario aprende

Idioma objetivo cuando pedagógicamente proceda.

El naming de unidad puede permanecer en target language si es una decisión pedagógica, pero debe ser consistente.

---

# 53. Accesibilidad: tap targets

Todos los elementos interactivos deben cumplir tamaño táctil adecuado.

Especial cuidado con:

```text
chips 5 / 10 / 15 / 20
CEFR selectors
lesson rows
small "change" links
```

No depender de tocar únicamente texto pequeño.

---

# 54. Accesibilidad: estrellas e iconos

VoiceOver/TalkBack no debe leer una secuencia de estrellas sin contexto.

Debe anunciar semánticamente:

```text
First sentences. Completed. 3 lessons.
```

La accesibilidad debe expresar el estado, no el dibujo.

---

# 55. Accesibilidad: progress bars

Una barra debe exponer semántica como:

```text
A1 progress, 62 percent
```

No únicamente decoración.

---

# 56. Accesibilidad: no usar color como única señal

Estados como current, completed, locked o weak deben distinguirse mediante texto, icono, forma y semántica, además de color.

---

# 57. Dynamic Type / font scaling

La pantalla debe funcionar con texto ampliado.

Especial riesgo:

```text
[A1] [A2] [B1] [B2] [C1]
```

y:

```text
[5 min] [10 min] [15 min] [20 min]
```

Si no caben, deben adaptarse, hacer wrap, convertirse en menú o usar scroll horizontal accesible.

---

# 58. Localización

Metadata compacta como:

```text
12 pts · 3 lecc · 0 list
```

puede romperse en otros idiomas y además es poco legible.

Preferir contenido más humano y mover estadísticas detalladas a una pantalla secundaria.

---

# 59. Estados vacíos

Deben diseñarse explícitamente.

## Usuario nuevo

No mostrar `0%`, `0 points` y `0 listenings` como protagonistas.

Mostrar:

```text
Tu primera sesión
5 min

Aprenderás a presentarte y tendrás tu primera conversación.

[ Empezar ]
```

---

# 60. Sin Recall pendiente

No dejar hueco vacío.

El Daily Plan simplemente utiliza otra actividad.

---

# 61. Sin listening/readings disponibles

El plan sustituye esa modalidad.

No mostrar un error de contenido en la experiencia diaria.

---

# 62. Sin curso para el idioma

Para idiomas aún no desarrollados:

```text
Estamos preparando este curso.
```

Si existen Talk u otras funciones:

```text
Mientras tanto puedes practicar conversación.
```

---

# 63. Curso completado

Estado:

```text
Has completado A1
```

Mostrar una siguiente acción:

```text
A2 está listo
[ Empezar A2 ]
```

o:

```text
Mantener A1
[ Sesión de repaso ]
```

---

# 64. Daily Plan completado

No regenerar infinitamente sesiones como si el usuario no hubiera terminado.

Después de completar:

```text
Entrenamiento de hoy completado ✓
```

Puede existir una acción secundaria `Practicar más`, pero no debe transmitir obligación.

---

# 65. Offline

Definir qué actividades funcionan sin conexión y cómo se representa una actividad temporalmente indisponible.

Ejemplo:

```text
Talk necesita conexión
```

sin romper el resto del plan.

---

# 66. Error de sync

No bloquear Aprender.

La UI puede seguir usando estado local y sincronizar después.

---

# 67. Loading states

Evitar una cascada de cards apareciendo en momentos diferentes.

Si Daily Plan, mastery y courses cargan de fuentes distintas:

- reservar layout;
- usar placeholders coherentes;
- evitar content jumping.

---

# 68. RecommendedLesson

La lógica actual de primera no intentada cuyos prerequisites tengan mastery suficiente es una buena base, pero UX necesita un caso adicional.

Si ninguna prerequisite cumple, no recomendar automáticamente la primera no intentada.

Mejor:

```text
¿lección elegible?
sí → recomendar
no → identificar prerequisite débil
   → recomendar review
```

---

# 69. Recommendation loop

Ejemplo:

```text
Quieres continuar con Past continuous,
pero Past simple necesita repaso.

[ Repasar Past simple · 4 min ]
```

Después:

```text
[ Continuar con Past continuous ]
```

Mucho más comprensible que saltarse prerequisites.

---

# 70. Recomendador y usuarios avanzados

No obligar al usuario a seguir siempre la recomendación.

Debe poder abrir `Ver temario completo` y elegir manualmente.

Recomendación no significa bloqueo.

---

# 71. Checkpoint y recommender

Si el checkpoint está listo, probablemente debe tener prioridad sobre una nueva lección.

Puede convertirse en la acción principal del path o entrar en el Daily Plan.

---

# 72. Diseño revisado de la pantalla

```text
┌─────────────────────────────────────────┐
│ Aprender                                │
├─────────────────────────────────────────┤
│                                         │
│ TU ENTRENAMIENTO DE HOY                 │
│ ┌─────────────────────────────────────┐ │
│ │ 10 min · 3 pasos                    │ │
│ │                                     │ │
│ │ ○ Repaso                     2 min  │ │
│ │ ○ Present continuous         4 min  │ │
│ │ ○ Talk: Right now            4 min  │ │
│ │                                     │ │
│ │ [ Empezar entrenamiento ]           │ │
│ │                         Cambiar 10m │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ TU CAMINO                               │
│ A1 · 8 de 12 unidades                   │
│ ████████████░░░░ 67%                    │
│                                         │
│ Preparación estimada · 58%              │
│                                         │
│ A1 · ACTUAL                             │
│ ─────────────────────────────────────── │
│ ✓ First sentences                       │
│ ✓ People & things                       │
│ ✓ Everyday actions                      │
│ → Right now                   1/3       │
│   Siguiente: Present continuous         │
│   [ Continuar ]                         │
│ ○ Getting around                        │
│ ...                                     │
│                                         │
│ A1 Checkpoint                           │
│ 🔒 4 unidades restantes                 │
│                                         │
│ A2 · 6 unidades                       › │
│ B1 · 4 unidades                       › │
│ B2 · 2 unidades                       › │
│ C1 · 1 unidad                         › │
│                                         │
│ [ Ver temario completo ]                │
│                                         │
│ TU PROGRESO                             │
│ Grammar     Sólido              72      │
│ Listening   En progreso         61      │
│ Speaking    Practicar           48      │
│ [ Ver habilidades ]                     │
│                                         │
│ ESPECIALIZACIONES                       │
│ English for Work · Recommended B1     › │
│ [ Ver todas ]                           │
└─────────────────────────────────────────┘
```

---

# 73. Variante cuando Recall es urgente

No crear una card independiente.

Integrarlo en el plan:

```text
Repaso recomendado
Tienes varios elementos importantes para refrescar.

○ Repaso                    4 min
○ Nueva lección             3 min
○ Talk                      3 min

[ Empezar 10 min ]
```

---

# 74. Variante checkpoint ready

```text
A1 CHECKPOINT READY

Has recorrido todo A1.
Comprueba qué puedes hacer sin ayudas.

Preparación estimada: 74%

[ Empezar checkpoint ]

Repasar antes
```

---

# 75. Variante plan en progreso

```text
Tu entrenamiento de hoy

1/3 completado
██████░░░░

✓ Repaso
→ Present continuous
○ Talk: Right now

6 min restantes

[ Continuar ]
```

---

# 76. Variante usuario nuevo

```text
Tu primera sesión

5 min

Aprenderás a presentarte
y tendrás tu primera mini conversación.

[ Empezar ]
```

No mostrar una gran cantidad de métricas vacías.

---

# 77. Arquitectura de navegación recomendada

```text
Aprender
│
├── Daily Session
│
├── Course / CEFR level
│   ├── Unit
│   │   └── Lesson
│   └── Checkpoint
│
├── Skills / Progress
│
└── Specializations
```

Recall puede ser una herramienta transversal accesible también desde Daily Session y Progress.

---

# 78. Scroll strategy

Al entrar desde navegación principal, comenzar arriba.

Al volver desde detalle de una unidad, restaurar posición.

No aplicar una única regla a ambos casos.

---

# 79. Sticky elements

No mantener demasiados elementos sticky.

Puede ser útil el navigation bar y, en un temario completo, el encabezado A1/A2.

No hacer sticky:

- Daily Plan entero;
- XP;
- múltiples chips.

---

# 80. Navegación a unidad

Lesson rows deben ser completamente clicables.

No obligar a pulsar un pequeño chevron.

---

# 81. Confirmaciones

No pedir confirmación para empezar lección, recall o listening.

Guardar confirmaciones para acciones destructivas.

---

# 82. Back behavior dentro de Daily Session

Si el usuario abandona:

```text
Guardar progreso de sesión
```

Al volver:

```text
Continuar entrenamiento
```

No empezar desde cero.

---

# 83. Analytics UX recomendada

Instrumentar:

```text
learn_screen_viewed

daily_plan_started
daily_plan_resumed
daily_plan_completed
daily_plan_budget_changed

course_level_expanded
course_level_collapsed

unit_opened
lesson_started

checkpoint_impression
checkpoint_started
checkpoint_completed

skills_summary_opened

specialization_opened
```

---

# 84. Métricas UX importantes

## Time to learning

Tiempo desde abrir Aprender hasta comenzar actividad.

Objetivo: reducirlo.

## Daily Plan start rate

```text
plan starts / Learn screen views
```

## Daily Plan completion rate

```text
completed / started
```

## Resume rate

Cuántas sesiones interrumpidas se continúan.

## Checkpoint discoverability

Cuántos usuarios preparados ven/inician checkpoint.

---

# 85. Hipótesis principal a validar

El rediseño debería conseguir:

```text
menos tiempo decidiendo
+
más tiempo aprendiendo
```

Métrica central:

```text
median seconds:
Learn screen opened → learning activity started
```

---

# 86. Performance UX

Aunque `LazyColumn` sea eficiente, evitar que cada row ejecute repetidamente cálculos como `recommendedLesson()`, `lessonAttempts()` o `masteryStats()` si pueden venir preparados desde ViewModel.

La UI debe consumir estado ya preparado.

---

# 87. LearnUiState recomendado

Conceptualmente:

```kotlin
data class LearnUiState(
    val dailyPlan: DailyPlanUiState,
    val currentPath: CoursePathUiState,
    val skillsSummary: SkillsSummaryUiState,
    val specializations: List<TrackUiState>,
    val checkpoint: CheckpointUiState?
)
```

La UI renderiza. No decide lógica pedagógica.

---

# 88. DailyPlanUiState

Debe incluir:

```text
budget
status
currentStep
steps
remainingMinutes
reason
```

---

# 89. CoursePathUiState

Debe incluir:

```text
currentCEFR
courseProgress
readiness
currentUnit
levels
```

No obligar a composables a reconstruir todo desde entidades raw.

---

# 90. Evitar layout shifts por mastery

Si mastery tarda algo más que course data:

```text
Tu progreso
[placeholder estable]
```

No insertar la sección de repente empujando todo el contenido.

---

# 91. Consistencia Android/iOS

El modelo UX debe ser común: mismas prioridades, estados y semántica.

Los componentes visuales pueden ser nativos de cada plataforma.

No buscar igualdad pixel-perfect; buscar igualdad funcional.

---

# 92. Visual hierarchy recomendada

Orden de énfasis:

## 1. Primary CTA

```text
Empezar / Continuar
```

## 2. Current learning context

```text
Right now
Present continuous
```

## 3. Progress context

```text
A1 67%
```

## 4. Secondary exploration

```text
Skills
Tracks
Full curriculum
```

XP queda por debajo de estas prioridades.

---

# 93. Qué eliminar de la vista principal

Recomiendo retirar del primer nivel visual:

- `12 pts`;
- conteo de listenings;
- LevelCard grande;
- todas las unidades de todos los niveles expandidas;
- Recall card duplicada;
- checkpoint al final de la pantalla;
- `Practised N×` como estado principal;
- mastery únicamente en Settings.

No significa eliminar esos datos del producto. Significa moverlos al lugar donde responden una pregunta concreta.

---

# 94. Qué conservar

Conservar:

- identidad de la página;
- Daily Plan;
- selector de tiempo;
- recommended lesson;
- CEFR grouping;
- course path;
- checkpoints;
- professional tracks;
- mastery;
- recall;
- lesson states.

Pero reorganizados.

---

# 95. Prioridades de implementación

## P0 — Corregir antes de escalar

### P0.1 Gamificación 0–10

La escalera deja de representar correctamente un curso que ya supera ampliamente el máximo original.

### P0.2 Checkpoint failures

Registrar intentos/resultados como evidencia.

### P0.3 Checkpoint placement

Mover checkpoint dentro del nivel correspondiente.

### P0.4 Daily Plan CTA

Crear `Start / Continue`.

### P0.5 Recall duplication

Integrar Recall dentro del plan diario.

---

# 96. P1 — Alto impacto UX

### P1.1 Progressive disclosure del currículo

Solo nivel actual expandido.

### P1.2 Current unit focus

Destacar y facilitar `Continue`.

### P1.3 Progreso contextual

Mostrar avance del CEFR actual en lugar del porcentaje gigante del curso como métrica principal.

### P1.4 Mastery discoverable

Resumen en Aprender.

### P1.5 Daily Plan con aplicación

Introducir Talk/speaking.

### P1.6 Session state

`notStarted / inProgress / completed`.

---

# 97. P2 — Refinamiento

- rationale de recomendaciones;
- estados `New / In progress / Review / Mastered`;
- specializations adaptadas a objetivo;
- copy;
- iconografía nativa;
- empty states;
- offline states;
- accesibilidad detallada;
- localization review.

---

# 98. P3 — Evolución

- plan altamente adaptativo;
- mastery dimensional;
- pronunciation summary;
- dynamic track recommendations;
- deeper progress insights;
- personal learning patterns.

---

# 99. Contradicción de datos que debe corregirse

La especificación contiene números incompatibles para el mismo curso.

En una parte se calcula el máximo teórico usando:

```text
43 lecciones
41 listenings
211 puntos
```

mientras que la tabla de organización posterior indica para English Foundations:

```text
25 unidades
77 lecciones
```

Esto debe resolverse antes de utilizar esos números para barras, porcentaje, nivel, analytics o UI.

## Recomendación

Ninguna cifra derivada del catálogo debe hardcodearse cuando pueda obtenerse del registry.

Ejemplo:

```text
course.lessonCount
course.listeningCount
course.maxAvailablePoints
```

Idealmente CI debe verificar estas métricas.

---

# 100. Acceptance criteria del rediseño

## Acción

- Existe exactamente una CTA primaria visible al entrar.
- Un usuario puede empezar una actividad en 1–2 taps.
- Si existe sesión empezada, la CTA principal es Continuar.

## Orientación

- El usuario identifica su nivel CEFR actual.
- El usuario identifica la unidad actual.
- El usuario entiende cuál es el siguiente paso.

## Carga cognitiva

- Los niveles futuros están colapsados.
- No existen dos CTAs prominentes para el mismo Recall.
- No se muestran simultáneamente más métricas de las necesarias.

## Mastery

- Existe acceso desde Aprender.
- Un skill sin evidencia no aparece como 0%.
- El usuario puede distinguir progreso de contenido y dominio.

## Checkpoint

- Está dentro del nivel CEFR.
- Puede conocerse antes de desbloquearlo.
- No desaparece después de aprobar.
- Los intentos fallidos generan datos.

## Accesibilidad

- Estados no dependen solo del color.
- Progress bars tienen semántica.
- Iconos tienen labels.
- Fila completa es tappable.
- Dynamic Type no rompe controles críticos.

---

# 101. Orden recomendado de trabajo

1. Simplificar la jerarquía visual a `Daily Plan → Current Path → Skills → Specializations`.
2. Eliminar duplicidad Recall.
3. Rediseñar navegación CEFR con progressive disclosure.
4. Mover checkpoint dentro del path.
5. Sustituir LevelCard 0–10 como métrica principal.
6. Añadir estado completo del Daily Plan.
7. Añadir Talk al plan adaptativo.
8. Añadir resumen de mastery.
9. Diseñar empty/loading/offline/completed states.
10. Instrumentar analytics UX.

---

# 102. Decisión recomendada

La pantalla **Aprender** no debería ser el inventario completo del sistema educativo.

Debe ser el **control center del aprendizaje del usuario**.

Debe responder rápidamente:

```text
¿Qué hago hoy?
¿Dónde estoy?
¿Qué estoy mejorando?
¿Qué viene después?
```

y dejar preguntas más profundas para vistas secundarias.

La mayor mejora posible no consiste en añadir más cards. Consiste en **reducir decisiones visibles y aumentar claridad de la siguiente acción**.

---

# 103. Versión final propuesta de arquitectura

```text
APRENDER

1. TODAY
   └── Daily Plan
       ├── Retrieve
       ├── Learn/Strengthen
       └── Apply

2. CURRENT PATH
   ├── Current CEFR
   ├── Current unit
   ├── Next lesson
   ├── Checkpoint
   └── Collapsed future levels

3. YOUR SKILLS
   ├── Summary
   └── Full mastery screen

4. SPECIALIZATIONS
   └── Relevant professional tracks
```

Gamificación:

```text
perfil / compact secondary status
```

Recall:

```text
integrado en Daily Plan
```

Checkpoint:

```text
dentro del nivel CEFR
```

Mastery:

```text
visible desde Aprender
```

Temario completo:

```text
progressive disclosure
```

---

# 104. Resultado esperado

El usuario debería pasar de experimentar la pantalla como:

> "Aquí está todo mi curso."

A:

> "La app sabe exactamente qué debería hacer ahora y puedo ver fácilmente hacia dónde voy."

Ese cambio es especialmente importante cuando el catálogo crezca a cientos de actividades y el aprendizaje deje de ser lineal.
