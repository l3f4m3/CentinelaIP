# Servidor de entrenamiento Centinela

El APK controla este servicio desde el teléfono: sube el dataset YOLO, inicia el trabajo, consulta el progreso y descarga el mejor modelo ONNX. El servidor debe ejecutarse en un PC o instancia con Python; una GPU CUDA es recomendable.

## Instalación

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export CENTINELA_TRAINING_TOKEN="cambia-este-token"
export CENTINELA_DEVICE="0"  # usa "cpu" solo para pruebas pequeñas
uvicorn server:app --host 0.0.0.0 --port 8000
```

En el teléfono configura `http://IP_DEL_PC:8000` y el mismo token. Mantén ambos dispositivos en una red confiable. Para acceso por Internet usa HTTPS mediante un proxy inverso, autenticación fuerte, límites de carga y firewall; no expongas directamente el puerto 8000.

El servidor usa `yolo26n.pt` como base. Puedes cambiarlo con `CENTINELA_BASE_MODEL`. La app vuelve a validar forma y operadores del ONNX antes de activarlo.

El dataset debe tener al menos una imagen en `train` y otra en `val`; el anotador del APK crea esa separación automáticamente a partir de dos o más muestras aceptadas. El servicio limita tamaño de carga, número de entradas y tamaño descomprimido para reducir riesgos de ZIP malicioso.

## API utilizada por el APK

- `GET /health`
- `POST /train` con `dataset` ZIP y `epochs`
- `GET /jobs/{job_id}`
- `GET /jobs/{job_id}/model`

El modelo descargable es un ZIP Centinela con `model.onnx`, `labels.txt` y `manifest.json`.
