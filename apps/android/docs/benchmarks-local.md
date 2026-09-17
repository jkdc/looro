# Benchmarks: motor local en Pixel 9 (y alternativas)

> Dispositivo de referencia: **Pixel 9 (Tensor G4, 12 GB, Android 17)**, estado
> térmico 1-2 persistente (big cores limitados a ~1,0-1,95 GHz de 3,3), batería
> sin ahorro de energía. Medición: 2026-09-17, rama
> `feature/switchable-conversation-engine`, kernels ggml ARMv8.2 con
> dotprod+i8mm (`GGML_CPU_ARM_ARCH`), 4 hilos, prompt de 379 tokens, respuesta
> de ~21 tokens salvo indicación.

## 1. Curva de tamaño de modelo (LLM, CPU, pesos en caliente)

| Modelo (Q4_K_M) | Tamaño | tok/s generación | Total por turno | Calidad percibida |
|---|---|---|---|---|
| Qwen2.5-**3B**-Instruct | 2,0 GB | **0,3-0,8** | 27-61 s | buena para coaching |
| Qwen2.5-**1.5B**-Instruct | 1,1 GB | **1,4-1,6** | 9-10,5 s | media |
| Qwen2.5-**0.5B**-Instruct | 0,5 GB | **1,8-2,8** | 9-13,6 s | básica |

Lecturas clave:

* El 1,5B es **~2× más rápido** que el 3B; el 0,5B solo añade ~1,7× más
  (existe un sobrecoste fijo por generación: prefill del prompt + térmica).
* El **throttling térmico es el factor dominante**: con el teléfono caliente,
  los big cores caen de ~2,6-3,3 GHz a ~1,0-1,95 GHz (Thermal Status 1-2) y
  todo el pipeline se degrada ~3-5×. Tras enfriar, mejora.
* Prefill (379 tok): ~15-24 tok/s con kernels optimizados. El prompt del sistema
  de voz (~600 tok con instrucciones completas) domina el TTFT de cada turno.
* STT (whisper base q5_1 multilingüe): 6 s de audio → 7,3-9,5 s (incluye carga
  del modelo la primera vez). RTF ≈ 1,2-1,5 en este estado térmico.
* TTS (sistema): ~4,3 s para una respuesta corta.

## 2. Estado térmico: causa raíz de la variabilidad

`dumpsys thermalservice` reporta **Thermal Status 1-2 (throttling)** de forma
persistente durante el uso, con el clúster LITTLE a 62 °C y los big cores
limitados a ~1,0-1,95 GHz (verificado con `/sys/devices/system/cpu/*/cpufreq/
scaling_cur_freq` durante generación). Implicaciones:

* La primera ronda tras enfriar el teléfono es la más rápida; rondas
  encadenadas degradan ~2-3× hasta estabilizarse.
* Cualquier optimización de CPU se mide SIEMPRE con el mismo estado térmico,
  o los números no son comparables.

## 3. Experimento GPU (Vulkan): bloqueado por toolchain

Intento de compilar ggml con `GGML_VULKAN=ON` para offload a la Mali-G720:
requiere en el host `find_package(Vulkan COMPONENTS glslc)` +
`find_package(SPIRV-Headers CONFIG)` (SDK de Vulkan). Este equipo no puede
instalarlos (sin sudo). Alternativas documentadas para un intento futuro:

1. `sudo apt install glslang-tools libvulkan-dev spirv-headers` (o el paquete
   `vulkan-sdk` de lunarG para Linux).
2. Descarga manual de shaderc (release binario) + Vulkan-Headers a un prefijo
   local y `CMAKE_PREFIX_PATH` apuntando ahí.
3. Con el build listo: exponer `n_gpu_layers` en el JNI (hoy está a 0 fijo) y
   repetir el benchmark. Mali-G720 vía Vulkan es viable pero menos maduro que
   Adreno; validar estabilidad.

Potencial esperado: 3-10× sobre CPU y mueve el calor de CPU a GPU, pero no está
verificado en este hardware.

## 4. Modelo "gratuito" vía API (Union Alpha Free en OpenCode Zen)

La clave facilitada (`OPENCODE`, válida: lista modelos correctamente) NO puede
consumir los modelos gratuitos del catálogo:

| Intento | Resultado |
|---|---|
| `union-alpha` (stream y no-stream) | `Internal server error` (también falla en el proveedor) |
| `mimo-v2.5-free`, `nemotron-3-ultra-free` | **`FreeTierError`: "free tier can only be used from within OpenCode"** — bloqueado al cliente OpenCode por diseño |
| `deepseek-v4-flash-free` | "Upstream request failed: Model is unavailable" |
| Modelos de pago (p. ej. `gpt-5.4-nano`) | `CreditsError: Insufficient balance` |

Conclusión: el "gratuito" de esta pasarela es un subsidio **dentro de su app**,
no un acceso API reutilizable. Para Mural, un modelo gratuito vía API exige un
proveedor con acceso API real (OpenRouter `:free` con clave propia, Groq tier
gratuito, etc. — y aun así: privacidad, límites y dependencia de terceros).

## 5. Comparativa final de opciones para la app

| Opción | Latencia por turno (Pixel 9) | Calidad | Coste | Estado |
|---|---|---|---|---|
| 3B local (actual) | 27-61 s + STT 7-9 s + TTS 4 s | buena | 0 | **medido** |
| 1.5B local | 9-10,5 s + STT + TTS | media | 0 | **medido** |
| 0.5B local | 9-13,6 s + STT + TTS | básica | 0 | **medido** |
| Vulkan GPU (futuro) | potencial 5-15 s | = modelo | 0 | bloqueado toolchain |
| API gratuita (Union Alpha y similares) | ~1-3 s | alta | 0 pero **no accesible desde fuera del cliente OpenCode** | **no viable** |
| **GLM-4.5-Flash vía api.z.ai (gratis con cuenta Zhipu)** | **1,2-2,0 s** | alta (modelo real, thinking off) | 0 | **verificado con clave real — recomendado para escritura** |

## 6. Recomendaciones

1. **Corto plazo**: para uso real en Pixel, el 1.5B es el mejor compromiso
   (9-10 s de generación + STT + TTS ≈ 20-30 s por turno). Probar calidad con
   tu idioma antes de decidir.
2. **Producto**: el modo local con 3B solo tiene sentido como "coach asíncrono"
   (respuestas escritas largas donde 30-60 s es tolerable), no como conversación
   hablada fluida.
3. **Técnico (si se apuesta por local)**: habilitar Vulkan/GPU (desbloquear
   toolchain) o migrar a Google AI Edge; ambos requieren esfuerzo de
   integración (estimación: 1-2 semanas) y validación en hardware.
4. **APIs gratuitas**: buscar proveedores con acceso API verdadero (no
   client-locked) y evaluar privacidad/limitaciones de tier gratuito.
