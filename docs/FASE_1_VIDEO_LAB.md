# Fase 1A · Laboratorio de video

## Resultado alpha02

La prueba física confirmó reproducción estable y temperatura controlada, pero reveló dos defectos:

- el video vertical era estirado para llenar una superficie horizontal, reduciendo la precisión de YOLO;
- el contador de FPS dependía de `TextureView.SurfaceTextureListener` y permanecía en 0 aunque el video se reprodujera con fluidez.

## Correcciones alpha03

- Lectura de fotogramas originales mediante `MediaMetadataRetriever`, sin deformación de la imagen usada por la IA.
- Transformación `FIT` del `TextureView` para conservar la relación de aspecto visible.
- Umbral experimental de detección reducido de 35% a 20%.
- FPS de video medidos con `VideoFrameMetadataListener` de Media3.
- Métrica separada de FPS de inferencia.
- Pruebas unitarias para video vertical, horizontal, escalado y rotación.

## Compuerta física alpha03

Validar en Pixel 9 y Pixel 10:

1. El video debe conservar su orientación vertical y no verse ensanchado.
2. `Video FPS` debe estabilizarse cerca de la tasa del archivo.
3. Deben aparecer detecciones en vehículos, personas y elementos COCO visibles.
4. La reproducción no debe congelarse durante 10 minutos.
5. Registrar latencia de IA, IA FPS, temperatura máxima y estado térmico.
