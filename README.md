# Centinela IP 0.4

Aplicación Android para reproducir varias cámaras IP por RTSP y el lente del teléfono al mismo tiempo, descubrir cámaras HCAM/X‑IOT en la red local, ejecutar detección YOLO26 por fuente, crear eventos configurables y preparar/entrenar modelos personalizados.

## Funciones

- Mosaico de hasta seis fuentes activas simultáneamente.
- Fuentes RTSP independientes con autenticación y transporte TCP/UDP.
- Cámara trasera o frontal del teléfono mediante CameraX.
- Asistente HCAM/X‑IOT por UUID: abre el panel Wi‑Fi, reconoce el punto de acceso, busca anuncios ONVIF, escanea el segmento local y prueba rutas RTSP con autenticación Basic/Digest.
- Detección YOLO planificada en turno rotativo entre todas las fuentes que tengan IA activada.
- YOLO26n/COCO incluido, inferencia local y etiquetas en español.
- Importación, validación, activación, eliminación y exportación de modelos ONNX.
- Reglas por clase para decidir qué mostrar, qué guardar como evento y qué notificar.
- Anotador de imágenes y videos fotograma a fotograma.
- Zoom 1–8×, paneo, creación, movimiento y ajuste por cuatro esquinas.
- Edición exacta de coordenadas normalizadas y nombres sugeridos, siempre modificables.
- Autoetiquetado con el modelo activo y estados `aceptada`, `revisar` y `rechazada`.
- Cola de revisión para reabrir, corregir o eliminar muestras.
- Exportación de dataset YOLO con separación `train/val` por fuente o bloque temporal.
- Entrenamiento iniciado y supervisado desde el teléfono mediante un servidor Python/GPU incluido.
- Autoaprendizaje por rondas: el mejor modelo reevalúa las cajas, conserva manuales y mejores predicciones, excluye las débiles y puede borrarlas si el usuario activa esa opción.

YOLO detecta la categoría **persona**, pero no identifica quién es una persona. El reconocimiento facial requiere otro modelo, consentimiento/enrolamiento y controles biométricos.

## Requisitos

- Teléfono ARM64 con Android 10 o superior.
- Para RTSP: URL real, credenciales y preferiblemente video H.264.
- Para cámara del teléfono: permiso de cámara.
- Para ajustar todos los pesos de YOLO26: PC/servidor Python, preferiblemente con GPU CUDA. El teléfono controla el trabajo, pero no finge que un ONNX de inferencia sea entrenable.

Las cámaras HCAM/X-IOT usan un UUID de la familia ThroughTek/Kalay (TUTK). Centinela no incluye bibliotecas copiadas de X‑IOT CAM/TinyCam. El enlace P2P remoto por UUID requiere el SDK oficial, una `licenseKey` y una `privateKey` asociadas al paquete de la aplicación. Sin esas claves, Centinela puede conectarlas localmente únicamente si el firmware publica RTSP u ONVIF. El asistente prueba primero `/11`, ruta documentada para dispositivos PPRT/HCAM relacionados, además de otras rutas comunes.

## Uso rápido

1. Instala el APK y abre **Fuentes**.
2. Añade **HCAM / X‑IOT**, **Cámara IP · RTSP** o **Cámara del teléfono**. Puedes activar varias a la vez.
3. Para una HCAM, conéctate a su red `XIOTA-HCAM…`, acepta permanecer sin Internet y pulsa **Detectar cámara**. La app obtiene el UUID del SSID cuando es visible; escribe las credenciales indicadas en la etiqueta o el manual del equipo.
4. En **Modelos**, importa/exporta paquetes o activa YOLO26n incluido.
5. En **Reglas de eventos**, marca por clase `Detectar`, `Guardar` y `Notificar`.
6. En **Anotar**, abre una imagen/video, dibuja cajas o pulsa **Sugerir con IA**. Revisa nombres y coordenadas antes de guardar.
7. En **Entrenar**, configura el servidor incluido, épocas y umbrales; inicia el trabajo y descarga el mejor modelo validado.

Consulta:

- [Anotaciones y autoaprendizaje](docs/ANOTACIONES_Y_ENTRENAMIENTO.md)
- [Formato de modelos importables](docs/FORMATO_MODELOS.md)
- [Servidor de entrenamiento](trainer-server/README.md)
- [Pruebas RTSP](docs/PRUEBAS_RTSP.md)
- [HCAM, UUID y múltiples fuentes](docs/HCAM_Y_MULTIFUENTE.md)
- [Señales, distancia y velocidad](docs/MODELO_TRAFICO.md)

## Compilación

Abre el proyecto en Android Studio, instala Android SDK 35 y ejecuta:

```bash
./gradlew assembleRelease
```

El APK queda en `app/build/outputs/apk/release/`. La configuración de ejemplo usa firma de desarrollo; configura un keystore propio y protegido para publicación o actualizaciones duraderas.

## Límites

- Hasta seis fuentes configurables; la cantidad que un teléfono puede decodificar a la vez depende de sus decodificadores de hardware, resolución y bitrate.
- Una sola fuente CameraX; Android no garantiza abrir los lentes frontal y trasero al mismo tiempo.
- El descubrimiento ONVIF local está incluido, pero no configura PTZ ni audio bidireccional.
- El P2P remoto HCAM/Kalay queda preparado para integrar un SDK autorizado; no funciona solo con el UUID porque TUTK valida las claves de licencia.
- El análisis se detiene cuando la aplicación pasa a segundo plano.
- El modelo COCO no reconoce señales específicas sin entrenamiento personalizado.
- YOLO solo entrega cajas/clases; no calcula por sí mismo distancia o velocidad física.
- La estimación monocular de tráfico no es un sistema ADAS homologado ni debe usarse para decisiones de conducción.

## Licencia

AGPL-3.0, compatible con los pesos YOLO26n incluidos. Revisa [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Para distribución cerrada o comercial de componentes Ultralytics se debe evaluar su licencia empresarial.
