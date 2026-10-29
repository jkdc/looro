# Corrección del seguimiento móvil V01–V07

## Objetivo y autorización

Petición: «resuelve los casos que has anotado en el documento».
Autoridad funcional: `informe-revision-seguimiento-app-movil-2026-10-29-segunda-pasada.md`, V01–V07 y sus regresiones.
Se implementan los siete casos en la rama activa, sin worktrees, push ni cambios de política local-first.

## Alcance

- V01: preservar procedencia y datos históricos antes del backfill en ambos stores.
- V02: corregir la accesibilidad del helper Swift sin ampliar API.
- V03: cercar carga y mutaciones iOS cuando falla activar/cargar una cuenta; reintento real.
- V04: usar una sola resolución de identidad en la proyección Android.
- V05: invalidar la tarjeta de examen al cambiar idioma.
- V06: cercar resultado y persistencia de pronunciación por cuenta/generación/idioma y liberar recursos al cancelar.
- V07: conservar audio oral ya admitido cuando otra locución prolonga el cierre de captura.

## Límites

No fusionar invitado/miembro, inferir orígenes o fechas, borrar datos ambiguos, introducir descargas recurrentes, cambiar esquema público o llamar proveedores reales.
iOS se implementa y se revisa estáticamente en Linux; compilación y ejecución quedan pendientes de macOS sin detener la implementación.

## Criterio de aceptación

Las regresiones detalladas en el informe quedan versionadas y se ejecutan donde hay runner determinista. Se conserva la evidencia RED/GREEN real y se informa de cualquier comprobación no ejecutada. Revisión independiente final y gates portables/Android antes de cerrar.
