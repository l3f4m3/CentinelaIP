# Componentes de terceros

## Ultralytics YOLO26n

- Modelo incluido: `yolo26n.onnx`
- Fuente: <https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n.onnx>
- SHA-256: `2e947b787d9e787b93a16772a5f55b1d4d8c4d86f53146149c5d6a642442d6f7`
- Metadatos: Ultralytics 8.4.38, COCO, entrada 640 × 640.
- Licencia: AGPL-3.0 o licencia empresarial de Ultralytics, según el uso.

El servidor opcional instala el paquete Python `ultralytics` para entrenar/exportar modelos y queda sujeto a sus términos.

## ONNX Runtime Android

- Proyecto: Microsoft ONNX Runtime.
- Licencia: MIT.
- Sitio: <https://onnxruntime.ai/>

## AndroidX

- Media3 / ExoPlayer, CameraX, Activity y ExifInterface.
- Licencia: Apache-2.0.
- Sitio: <https://developer.android.com/jetpack/androidx>

## Servidor opcional

- FastAPI, Uvicorn, PyYAML y python-multipart.
- Consulta las licencias de las versiones instaladas por `trainer-server/requirements.txt`.

No se incluyen SDKs propietarios de fabricantes de cámaras, reconocimiento facial ni servicios de analítica remota.

## ThroughTek / Kalay (TUTK)

Centinela identifica dispositivos que usan la plataforma Kalay y deja un punto de integración para un SDK autorizado, pero **no distribuye** `IOTCAPIs`, `AVAPIs`, bibliotecas de X‑IOT CAM/TinyCam ni claves TUTK. El enlace P2P se habilitará únicamente con un paquete y credenciales de licencia proporcionados por ThroughTek o por el fabricante del dispositivo.
