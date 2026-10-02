# Informe: repaso del proyecto y propuesta de mejoras y features — 2026-09-22

Rama: `feature/rebranding-looro` · HEAD: `64f7b82` · Método: exploración de repositorio + contexto de informes anteriores.
Las cifras de tests citadas son las últimas verificaciones conocidas (no se re-ejecutaron en este repaso).

---

## 1. Foto del estado actual

**Qué es Palique hoy:** app dual-nativa (SwiftUI iOS + Jetpack Compose Android, sin framework compartido,
paridad vía `check_cross_platform.py`), backend Fastify 5 + Postgres con economía de minutos
(Play Billing + Stripe + minutos de invitado/bienvenida), voz hosted por WebRTC con STT on-device (whisper.cpp)
y TTS local, motores de conversación seleccionables en runtime (OpenAI/DeepSeek/GLM) con claves por proveedor
en Keystore desde `provider-key-selection`. Panel de administración completo (Tabler, 8 vistas, acciones auditadas,
login Google authorization-code). 13 cambios openspec, 12 verificados al 100%.

**Superficie ya construida (todo verde en su momento):**

| Área | Estado |
|---|---|
| Conversación hablada (VAD adaptativo, turnos, temas con anclaje) | ✅ verificada |
| Cursos y lecciones (tab Learn, tarjetas de estudio, práctica) | ✅ Android 406/406; F3 (barras de evidencia) y F4 (autorar 17 lecciones) aparcados |
| Informes IA de sesión + hosted conversations + helpers en iOS | ✅ flag off por defecto en iOS |
| Selección de motor + claves por proveedor (BYOK parcial) | ✅ Android 411/411 |
| Economía de minutos (compras, ledger, guest/welcome, webhooks Stripe) | ✅ |
| Panel admin (browse, dashboards, política, campañas, auditoría) | ✅ falta cliente OAuth real + DNS + servidor |
| Seguridad backend | Sólida; 3 gaps conocidos (ver §3.C) |

**Pendiente conocido (de informes y memoria de trabajo):**
- `rebranding-looro` T3 sin marcar: renombrado backend (paquete, BDs, role, locks, HMAC).
- Gates de macOS y de dispositivo real pendientes en las últimas 5 features (todo verificado en emulador/racional).
- Backlog aparcado del cableado: strict listening, device gates, delegación, superficie legacy backend.
- Decisión de backend Go aparcada (veredicto anterior: load-test primero, no port).
- Billing elegido por ti pero sin SDD: unlock BYOK único + tiers mensuales de tokens vía proxy propio (análisis de coste MiniMax pendiente).

---

## 2. Diagnóstico: tres desequilibrios

1. **Producto vs contenido.** Hay más código de monetización (dos stores, ledger, webhooks, proxy de pagos)
   que contenido que monetizar: `shared/courses/en-foundations.json` tiene **1 lección de las 18 del temario**
   de English Foundations. El cuello de botella del producto ya no es técnica, es authoring.
2. **Verificación local vs gates reales.** Todo lo reciente pasó Android-emulador + verificación racional,
   pero macOS y dispositivo físico acumulan 5 features sin gate. El riesgo de regresión se concentra justo
   en lo último construido (iOS parity, provider keys, panel).
3. **Infra local vs producción.** Panel admin y backend no están desplegados (sin DNS ni cliente OAuth real).
   El producto no tiene aún un servidor que un usuario externo pueda tocar.

Además, un factor estructural: cada feature de producto se paga dos veces (Kotlin + Swift). El generador
`export_courses.py` ya resuelve la parte de cursos; el resto (temas, teaching guidance, políticas) sigue
duplicado a mano.

---

## 3. Mejoras propuestas

### A. Contenido y aprendizaje

| # | Mejora | Qué es | Esfuerzo |
|---|---|---|---|
| A1 | **Terminar el temario (F4)** | Autorar las 17 lecciones restantes de English Foundations en `shared/courses/en-foundations.json`; el pipeline ya genera Kotlin/Swift. Es la mejora con mayor retorno por euro invertido del proyecto entero. | M (contenido, no código) |
| A2 | **CMS de cursos en el panel admin** | Vista de edición de cursos en `admin-ui` sobre el JSON fuente: validación de esquema, previsualización y export al repo. Hoy editar contenido es editar JSON a mano en el repo. Convierte el authoring en tarea de operador, no de dev. | L |
| A3 | **Barreras de evidencia (F3)** | Aparcado del change `courses-and-lessons`: mostrar evidencia de dominio en las tarjetas de lección. Cierra el loop pedagógico del Learn tab. | S-M |
| A4 | **Extender el generador a temas y guidance** | Llevar temas/guidance de los 8 módulos de idioma a JSON fuente + generación, como ya pasa con cursos. Reduce a la mitad la superficie de la paridad Kt/Swift en el dominio que más cambia. | M |

### B. Monetización e infraestructura

| # | Mejora | Qué es | Esfuerzo |
|---|---|---|---|
| B1 | **Desplegar a producción** | Servidor + DNS + cliente OAuth real del panel + `compose.yaml` + Caddy. Sin esto, nada del backend/panel es real para usuarios. | S-M |
| B2 | **SDD del billing elegido** | Unlock BYOK único + tiers mensuales de tokens vía proxy propio. Pendiente el análisis de coste (MiniMax) y el rateVersion/nanoUSD que ya se asesoró. Es el único bloque de ingresos no implementado. | L |
| B3 | **Load-test del backend** | k6/Artillery sobre `/v1/live` y economía de minutos. Era el prerequisito de la decisión Go y además endurece el dimensionamiento antes de cobrar dinero real. | S |

### C. Calidad, seguridad y lanzamiento

| # | Mejora | Qué es | Esfuerzo |
|---|---|---|---|
| C1 | **Cerrar rebranding T3** | Renombrado backend (paquete, BDs, role, locks, HMAC): última tarea sin marcar del change de branding. Mejor hacerlo antes del primer deploy (B1) porque renombra BDs. | S-M |
| C2 | **Gates macOS + dispositivo** | Ronda de verificación de las 5 features pendientes en macOS/iPhone real + Android físico. Convierte "razonado" en "probado". | M (acceso a Mac) |
| C3 | **Hardening backend (3 gaps conocidos)** | Limiter para rutas exentas de proxy, TTL de sesión admin < 24h, attestor de invitados no spoofable. Ya diagnosticados en la auditoría del 2026-09-21. | S-M |
| C4 | **Track interno de Play** | Subir el artefacto ya verificado a internal testing; activa distribución real y Feedback sin abrir al público. | S |

### D. Experiencia de desarrollo e higiene

| # | Mejora | Qué es | Esfuerzo |
|---|---|---|---|
| D1 | **Orden del raíz del repo** | 10 informes `informe-*.md` + `e2e-evidence/` + `release/` en el raíz. Mover informes a `docs/informes/` y gitignorar evidencias/artefactos. | S |
| D2 | **Recortar whisper.cpp del submódulo** | El checkout arrastra ejemplos y modelos del upstream. Ajustar sparse-checkout del submódulo reduce clon y build. | S |
| D3 | **CI: job E2E nocturno** | La receta de emulador ya existe (AVD quirks documentados); subirla como workflow nightly da el gate de regresión de UI que hoy es manual. | M |
| D4 | **Crash reporting opt-in** | Hoy "sin analytics" es postura de producto (y es un diferencial). Si se quiere telemetría de crashes, hacerla opt-in explícita y propia (endpoint en el propio backend), no un SDK de terceros. Es decisión de producto, no técnica. | M |

---

## 4. Nuevas features propuestas

1. **Repaso espaciado (SRS) + libreta de vocabulario.** Ya existen `WordPractice`, `MeaningController`,
   `FinalAssessmentQueue` y `WordLookupSheet` — la base natural para una cola de repaso con espaciado
   (Leitner/SM-2 simplificado) alimentada de las expresiones objetivo de lecciones y del vocabulario que
   el usuario consulta en conversación. Pantalla "Repasar" en el Learn tab + notificación local.
   Es la feature de retención con mejor relación esfuerzo/impacto y no existe nada de SRS hoy.
2. **Test de nivelación + rutas por nivel (CEFR).** Colocar al usuario en A1/A2/B1 al terminar onboarding
   y ordenar/filtrar el temario en consecuencia. Con 1 curso aún no urge, pero conviene diseñar el JSON
   de cursos con nivel por unidad **antes** de autorar 17 lecciones (A1) para no reautorar.
3. **Notificaciones locales de práctica.** Las rachas ya viven en `LearningEngine` (ambas plataformas);
   falta el recordatorio diario y el objetivo configurable (X minutos/día). Solo notificaciones locales,
   sin servidor de push — coherente con la postura de privacidad.
4. **Modo escucha estricta.** Ítem del backlog de cableado: ocultar transcripción durante el turno del
   tutor para forzar comprensión auditiva, con toggle por usuario en Settings. Pequeño y pedagógicamente potente.
5. **Selección de acento/variante de TTS.** Elegir variedad (p. ej. inglés US/UK) o voz del tutor entre las
   disponibles del TTS local. Barato si `LocalTtsEngine` expone el catálogo del sistema; buen diferenciador
   percibido.
6. **Exportar/compartir informe IA y transcripción.** `ReportConversationSheet` ya renderiza el informe;
   añadir share sheet (PDF/texto) es pequeño y alimenta el boca a boca.
7. **Activar más idiomas.** Hay 8 módulos de idioma ya implementados en código (noruego, español, inglés,
   francés, alemán, italiano, portugués, mandarín) pero solo English Foundations tiene curso. Un piloto
   (p. ej. "Spanish Foundations" para hispanohablantes de otra L1, o Norwegian dado el origen del proyecto)
   reutiliza el 95% de la maquinaria. Requiere decisión editorial: qué idioma y qué alcance.

No propuestas a propósito: rachas y estadísticas (ya existen), leaderboard/social (fuera de la postura
de privacidad), web app (duplicaría plataforma tercera sin motor de voz local).

---

## 5. Priorización sugerida

**Fase 1 — Hacer el producto real (cerrar lo abierto):**
A1 (temario completo, con nivel CEFR por unidad desde el diseño) → C1 (T3 rebranding, renombra BDs) →
B1 (deploy) → C2 (gates macOS/device) → C4 (track interno Play).
Razón: nada de lo demás importa hasta que exista contenido que aprender y un servidor al que conectarse.

**Fase 2 — Dinero:**
B3 (load-test) → B2 (SDD billing: BYOK unlock + tiers de tokens, con el análisis MiniMax y el
rateVersion/nanoUSD ya asesorado).

**Fase 3 — Retención y crecimiento:**
Feature 1 (SRS + libreta) → 3 (notificaciones) → 4 (escucha estricta) → 6 (share informe); en paralelo
A2 (CMS de cursos) para que el authoring deje de ser tarea de dev, y A3 (F3 evidencia).

**Continuo:** C3 (hardening) y D1-D3 (higiene) pueden entrar en cualquier hueco; D4 queda como decisión
de producto abierta.

---

## 6. Veredecto

El proyecto está **sobre-construido en infraestructura y infra-dotado en contenido**: dos tiendas, un panel
de administración profesional y una economía de minutos conviven con una sola lección autorizada. La técnica
ha ido siempre por delante (verificada, auditada, con paridad), así que la mejor "nueva feature" posible no es
más código de plataforma sino **contenido (F4 + diseño CEFR), un servidor (deploy) y el SDD de billing** —
en ese orden. De las features nuevas, el repaso espaciado es la que mejor aprovecha motores que ya existen
y la que más retención añade por esfuerzo invertido.
