# Proposal: daily-practice

## Por qué

Ofrecer una razón clara para practicar cada día: una experiencia breve y
coherente, en lugar de pedir al alumno que construya su sesión combinando
actividades independientes. El cierre convierte la práctica en acciones de
mejora y en material reutilizable en Words.

## Requisitos confirmados

| ID | Requisito y evidencia de la conversación |
|---|---|
| U1 | «cada día se genere un temario de speaking, listening y practica simple de lo aprendido» |
| U2 | «tras la revisión de todo por parte de la ia pase una revisión aal usuario de donde ha cometido fallos y qué tiene que mejorar también» |
| U3 | «los usuarios/llm añadan las palabras, expresiones donde han encontrado más problemas» |
| U4 | «tanto a la app movil y la api y también el panel de administración» |
| U5 | «15/20 minutos máximo» (revisado después: duración orientativa, no límite) |
| U6 | «de momento sólo 1 para todos»; personalización y variantes por nivel quedan para el futuro |
| U7 | «preparado para cualquier idioma»; «solo empezaremos con inglés» |
| U8 | «debería haber un topic, el speaking (conversacion debería estar preparado para ellos)» |
| U9 | «los resultados de palabras, expresiones finales deberían ser añadidos a la página de words» |
| U10 | «dificultad intermedia/alta pero que sirva para los alumnos de nivel bajo para aprender también» |
| U11 | Aprobado el enfoque de reto común, ayudas opcionales, práctica breve y revisión que distingue ejecución con apoyo de dominio independiente |

## Alcance de la primera versión

- Paquete por idioma y fecha editorial; solo `en` activado.
- Topic, objetivo comunicativo, vocabulario objetivo, listening con preguntas,
  briefing de speaking y ejercicios comunes con respuestas y explicaciones.
- Ayudas a demanda disponibles para cualquiera, no asignadas por nivel.
- Generación anticipada, validación determinista, juez independiente, publicación
  del artefacto exacto revisado y herramientas administrativas de control.
- Intentos privados, recuperación tras interrupciones, evaluación individual y
  aplicación local idempotente del resultado a Words en Android e iOS.
- Arquitectura extensible mediante perfiles lingüísticos versionados.

## Alternativas consideradas

| Enfoque | Ventaja | Coste o motivo de descarte |
|---|---|---|
| Generación por alumno y dificultad adaptativa | Mayor ajuste individual potencial | Fuera del alcance indicado; multiplica generación, estado y evaluación |
| Contenido totalmente manual | Control editorial directo | No satisface por sí solo la generación diaria solicitada |
| **Paquete común generado, validado y congelado + evaluación individual** | Reparte el coste del contenido y mantiene una experiencia común | Exige controlar calidad y ofrecer ayudas para principiantes |

La tercera opción materializa el enfoque confirmado. No se introducen temarios
por nivel «por si acaso»: el esquema permite futuras extensiones, pero no
implementa un selector ni poblaciones de alumnos.

## Fuera de alcance

Notificaciones, nuevas rachas/recompensas, rankings, certificados, cambio de
nivel CEFR, repetición espaciada personalizada del temario, nuevos planes o
precios, sincronización general de Words entre dispositivos, soporte productivo
de otros idiomas, speaking BYOK dentro del recorrido y generación
multimedia de imágenes. La práctica libre y el repaso existentes permanecen.

La incorporación a Words usa su mecanismo actual de repaso; no constituye una
personalización del paquete diario. No se amplían los exámenes semanales ni se
reutilizan sus resultados como si esta práctica fuera un examen.

## Encaje en el repositorio

- `DailyPlanEngine` en ambos cores devuelve `PlanStep(kind, minutes)`; es un
  recomendador local de actividades existentes, no un intento diario persistido.
- La conversación de escenarios y su revisión son referencias reutilizables;
  los textos OpenSpec históricos no demuestran la cobertura actual de iOS.
- Words ya incluye palabras y expresiones. Se conserva su proyección y repaso;
  no se crea una segunda biblioteca de vocabulario.
- La guía de incorporación de idiomas de exámenes enseña la frontera entre
  motor común y validación lingüística específica. No habilita automáticamente
  un idioma para este modo.

Los puntos concretos de integración están en [design.md](design.md). Ningún
fichero generado `*Data.kt`/`*Data.swift` se editará manualmente.

## Contención y reversibilidad

Separar interruptores de lectura, nuevos intentos, generación y publicación.
Desactivar nuevos intentos no elimina los empezados ni sus revisiones. Retirar
un paquete impide nuevas sesiones y bloquea nuevos turnos del contenido retirado;
los resultados previos siguen disponibles salvo causa de privacidad/seguridad.

Migraciones aditivas, sin modificar filas históricas, ledger financiero ni
formato de progreso existente. Una reversión desactiva las rutas nuevas pero
mantiene datos y colas recuperables; no borra trabajo del alumno.
