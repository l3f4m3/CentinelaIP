# Fase 0 — Línea base verificable

## Objetivo

Establecer una base reproducible antes de incorporar distancia monocular, fusión sensorial, carriles, baches y escena 3D.

## Video de referencia

- Archivo: `1000417446.mp4`
- SHA-256: `e0f51c8ebb5aec28fe051fa8f091e35a8b8f13aa6ccd2339b5d6bfbed294e021`
- Duración: `17.844767 s`
- Resolución: `478 × 850`
- Video: H.264, YUV420p
- Audio: AAC
- Tasa media aproximada: 23.98 FPS

El video no se incorpora al repositorio para evitar inflarlo. Su hash permite verificar que las pruebas futuras usan exactamente el mismo archivo.

## Compuerta de aceptación

La fase solo se considera cerrada cuando:

1. Las pruebas unitarias del estimador de riesgo pasan.
2. La APK release compila con Java 17 y Android SDK 35.
3. El APK es un ZIP íntegro.
4. `apksigner` valida la firma y muestra el certificado.
5. Se publica el APK junto con SHA-256 y reporte de pruebas.
6. La instalación y apertura se validan físicamente en Pixel 9 y Pixel 10.

Los puntos 1–5 se ejecutan en GitHub Actions. El punto 6 exige prueba real en los dispositivos; un emulador no valida CameraX, decodificación, temperatura ni permisos del hardware real.

## Motor de riesgo inicial

`RiskEstimator` define una interfaz determinista para convertir distancia, velocidad de cierre y confianza en:

- `UNKNOWN`
- `SAFE`
- `ATTENTION`
- `WARNING`
- `CRITICAL`

Es una línea base de software, no un sistema ADAS homologado. Los umbrales se recalibrarán con telemetría y datos etiquetados.
