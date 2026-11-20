# Roadmap de rebranding de Palique

**Logo preferido:** P de Palique. La [comparativa visual](direcciones.html) mantiene ese logo fijo y muestra tres estilos sobre las mismas escenas. Elegir el logo no aprueba el estilo ni autoriza cambios en las apps.

## Objetivo: cambiar un tema, no reescribir pantallas

Una pantalla pide un **rol** como «fondo», «texto sobre acción» o «estado de voz», nunca un nombre de color ni un valor hexadecimal. Un perfil asigna valores a esos roles. Los adaptadores nativos traducen el perfil a componentes de cada plataforma.

| Capa | Responsabilidad |
|---|---|
| Catálogo común | Una fuente versionada define perfiles, roles y parejas de contraste. Separar dirección de marca y modo claro/oscuro si más adelante se admiten ambos. |
| Adaptadores nativos | En Android, mapear los roles base al esquema de Material 3 y proporcionar los específicos de Palique desde el tema. En iOS, inyectarlos por el entorno de SwiftUI. Respetar tipografía de respaldo y escalado del sistema. |
| Componentes y activos | Cabecera, orbe, navegación, botones, tarjetas y avisos consumen roles; logo e iconos son activos separados. El sistema no necesita un selector público ni descargar temas por red. |

Roles mínimos: fondo, superficie, texto, texto secundario, acción principal y su texto, acento y su texto, borde, foco, error y su texto, éxito y su texto, superficie de conversación, texto de conversación y onda de voz. Definir también roles acotados de tipografía, espaciado, radios y movimiento.

Hoy Android configura un esquema claro en [Design.kt](../../apps/android/app/src/main/java/com/jkdc/app/ui/Design.kt), pero componentes y dibujos también consumen AppColors directamente. iOS concentra constantes en [Design.swift](../../apps/ios/App/Design.swift). Empezar por esos puntos. Un catálogo común cuesta un pequeño paso de validación, pero evita mantener dos paletas a mano; generar el mapa nativo si el pipeline existente lo permite sin añadir dependencias de ejecución.

## Fases

| Fase | Entregable | Criterio de salida |
|---|---|---|
| **0. Cerrar diseño** | Elegir A, B o C; pulir la P, firma e iconos; definir la superficie de sesión. Inventariar activos y capturas. | Silueta legible en monocromo, en tamaños pequeños y con máscaras de Android e iOS. Dirección aprobada. |
| **1. Fundamentos** | Incorporar roles y adaptadores nativos. Migrar inicialmente la apariencia actual a un perfil de referencia y preparar un perfil alternativo de prueba. | Cambiar perfil sin editar pantallas; detectar roles ausentes, verificar contraste y comprobar que las dos plataformas consumen los mismos valores previstos. |
| **2. Piloto de conversación** | Migrar Hablar, sesión activa, entrada escrita, traducción, transcripción, orbe y permisos de micrófono. | Pruebas de voz/escritura y estados listo, escuchando, hablando y apagado; error, reintento, cancelación y texto ampliado. |
| **3. Resto de la app** | Migrar Temas, Aprender, Palabras, ejercicios, exámenes, onboarding, ajustes, cuenta y compras. | Recuerdo y avisos comprensibles sin depender del color; navegación, progreso, evaluación y lógica de compra sin cambios. |
| **4. Activos y salida** | Cambiar icono y arranque; actualizar README, capturas y fichas de tienda; alinear materiales públicos que estén en alcance. | Capturas reales y textos EN/ES revisados; conservar el perfil anterior como reversión visual durante la transición. Publicar solo con autorización. |

### Demostración de que el tema es fácil de cambiar

En **ambas apps**, seleccionar un segundo perfil interno y comprobar que el botón principal, la cabecera, la superficie y el orbe cambian sin tocar las pantallas ni sus acciones. Añadir pruebas de roles incompletos y contraste de pares reales. Revisar lector de pantalla, foco, texto ampliado y movimiento reducido en dispositivo. La comprobación nativa de iOS requiere macOS/Xcode.

El alcance es identidad y experiencia visual móvil; no cambia API, persistencia, pedagogía ni panel de administración. Con la dirección aprobada, este roadmap se traduce en una especificación y un plan de implementación por fases.

## Piloto seleccionado: A · Conversación editorial

Implementación inicial: [OpenSpec](../../openspec/changes/editorial-mobile-pilot/proposal.md). La fuente de verdad de los colores y del estilo del titular es [mobile-theme.json](../../shared/design/mobile-theme.json). Tras editarla, ejecutar desde la raíz:

```sh
node scripts/export_mobile_editorial_theme.mjs
node scripts/check_mobile_editorial_theme.mjs
```

No editar a mano los bloques generados. El perfil de referencia es una alternativa **del piloto**, no una restauración completa del diseño previo. Para probarlo sin editar pantallas, suministrar `ConversationPalette.reference` a `AppTheme` en la raíz de Android y añadir `.environment(\.conversationPalette, .reference)` en la raíz de SwiftUI. Revisar cabecera, bienvenida, orbe, botón principal, sesión activa y traducción; comprobar que voz, escritura y los controles siguen funcionando. El icono de lanzamiento, la tienda y las demás pantallas siguen siendo fases posteriores.
