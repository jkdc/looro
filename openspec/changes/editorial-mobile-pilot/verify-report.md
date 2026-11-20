# Verificación del piloto editorial

- **Contrato compartido:** la prueba falló antes de conectar los bloques nativos; pasa con los dos perfiles generados. Comprueba integridad, tipografía definida por perfil, sincronización de los bloques y contraste mínimo 4,5:1 en los pares principales.
- **Revisión estática independiente:** detectó que el estilo del titular no dependía del tema y que el SVG de iOS podía ser más explícito. Ambos puntos se corrigieron.
- **iOS:** compilación, recursos y flujo nativo pendientes; requieren macOS/Xcode.
- **Comprobación visual en dispositivo:** queda pendiente cambiar de perfil en ambas apps y recorrer voz, escritura, traducción y cancelación. El contrato compartido no prueba la navegación ni el renderizado nativo.
- **Android:** Kotlin compilado y `assembleDebug` completado; se generó `apps/android/app/build/outputs/apk/debug/app-debug.apk`. La ejecución combinada de pruebas unitarias y lint no había emitido un resultado final al cerrar este informe.
