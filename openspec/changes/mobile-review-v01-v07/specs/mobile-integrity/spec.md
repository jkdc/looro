# Integridad móvil V01–V07

## V01 — Procedencia antes de normalización
Con payload pendiente, sin claim/hash y contenido pre-owner ambiguo, abrir no debe cambiar filas, mastery, backfill, identidad persistida ni fuente. Debe rechazar conservando estado. BD fresca, claim ajeno y migración legítima conservan su contrato.

## V02 — Acceso Swift
El helper que devuelve LegacyResidue no debe tener acceso más amplio que su tipo de resultado.

## V03 — Cuenta iOS cargada y segura
Un fallo de activación o carga de B no permite escrituras de B bajo A ni reemplazar datos de B desde una proyección vacía. Reintentar B debe ejecutar realmente la carga. Todas las mutaciones durables deben exigir contexto cargado vigente; el estado fallido es observable y reintentable.

## V04 — Proyección Android
Tras cargar una cuenta miembro y sus rollups, solicitado/cargado/consultado coinciden sin resolver dos veces. Durante cambio de cuenta no se presentan datos de la anterior.

## V05 — Tarjeta por idioma
La tarjeta muestra únicamente el último resultado del idioma/nivel/cuenta actuales. EN→ES→EN con el mismo nivel invalida correctamente el resultado derivado.

## V06 — Pronunciación contextual
Resultado, aviso y persistencia se publican solo si cuenta/generación/idioma siguen siendo los capturados al iniciar. Cambiar contexto invalida/cancela la práctica; recursos se liberan incluso al cancelar. Éxito estable registra una vez; cancelación no se convierte en error de usuario.

## V07 — Prefijo oral retenido
El audio admitido antes de TTS permanece ordenado y limitado mientras otra locución continúe, aunque la apertura haya terminado. Finalizar produce exactamente una respuesta cuando puede drenarse. Audio nuevo durante TTS sigue excluido; mute y cancelación conservan su contrato.
