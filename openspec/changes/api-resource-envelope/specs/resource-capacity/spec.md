# Requisitos de capacidad acotada

## S1 — Presupuesto total verificable
SHALL ofrecer perfiles cerrados de 1 vCPU/4 GB, 2 vCPU/8 GB y 4 vCPU/12 GB conjuntos para API, BD, edge y observabilidad activa. SHALL comprobar límites efectivos y componentes omitidos; driver/stub no representan consumo del servicio y se reportan aparte. SHALL NOT activar autoscaling ni tocar Coolify.

## S2 — Comparación y primeras concesiones
SHALL comparar `aa51066` y `2b61585` con cuotas, imágenes, datos y orden intercalado iguales. SHALL separar perfiles reclamado, guest/activo y elegible nuevo, sin que preflight consuma cohortes nuevas. SHALL preservar controles financieros y documentar ventajas/regresiones sin inferir más capacidad de menos SQL.

## S3 — Tráfico y TLS válidos
SHALL soportar TLS local verificado y control directo, destinos cerrados y preflight de identidad. SHALL validar respuestas por método/ruta/status, incluida sincronización 202 con efecto persistido y exámenes reales existentes; errores/política/no emitidas SHALL NOT contar como éxito. SHALL conservar smoke/baseline actuales.

## S4 — Resistencia y recuperación
SHALL ejecutar escalones por presupuesto y al menos un soak de una hora en el punto sostenible seleccionado, con ventanas acotadas, datos suficientes y medición de recursos. SHALL observar fallos de proveedor/reinicios API/BD en proyectos sintéticos propios, recuperación y conciliación financiera, sin débitos o reintentos externos inventados. SHALL dar previsión de coste/duración antes de ensayo largo.

## S5 — Auxiliares y Redis
SHALL distinguir panel estático de jobs y dashboards; no apagar Caddy/API para apagar admin. SHALL enumerar auxiliares activos y medir costes. Redis SHALL evaluarse y solo proponerse/incorporarse con beneficio demostrado, diseño de invalidación/fallo y pruebas; no cachear saldo/autorización. Sin integración de dependencia productiva por intuición.

## S6 — Análisis móvil y entrega
SHALL analizar Android/iOS después de los ensayos: rutas, disparadores, batching, datos locales, caché, lifecycle, reintentos/voz y bytes. SHALL separar evidencia del código de supuestos ligero/típico/intensivo y uso real no observado; contrastar mezcla de ensayo y repetir validación acotada si difiere. SHALL reportar capacidad por perfil, límites/pendientes, disco/red y guía Coolify no ejecutada; sin despliegue/push/PR/merge ni modificación automática móvil.
