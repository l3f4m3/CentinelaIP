# Detección de señales, distancia y velocidad

## 1. Entrenar señales de tránsito

Sí se puede ajustar YOLO26 para clases como `pare`, `ceda_el_paso`, `limite_30`, `limite_50`, `cruce_peatonal` o `semaforo`. Conviene entrenar solo las señales del país o región donde se utilizará.

El conjunto de datos debe contener anotaciones de cajas, variedad de tamaños y escenas de día, noche, lluvia, contraluz y oclusión. Separa entrenamiento y validación por recorridos completos; fotogramas vecinos de un mismo video no deben quedar en ambos grupos porque darían una validación engañosa.

Ejemplo de entrenamiento:

```bash
pip install ultralytics
yolo detect train model=yolo26n.pt data=senales.yaml epochs=100 imgsz=640
yolo detect val model=runs/detect/train/weights/best.pt data=senales.yaml
yolo export model=runs/detect/train/weights/best.pt format=onnx imgsz=640 simplify=True
```

Antes de desplegar, valida por separado ciudad/carretera, día/noche y condiciones meteorológicas. Centinela IP 0.4 permite anotar el material, controlar el entrenamiento desde el teléfono e importar automáticamente el mejor paquete ONNX compatible.

## 2. Distancia al vehículo delantero

YOLO entrega una caja alrededor del vehículo, no distancia física. Una aproximación monocular requiere calibrar la cámara. Si se conoce el ancho real aproximado del vehículo, la distancia puede estimarse como:

```text
distancia ≈ distancia_focal_en_píxeles × ancho_real / ancho_de_la_caja_en_píxeles
```

Los distintos tamaños de vehículo producen error. Para mejorarla se puede usar el plano de la carretera con altura e inclinación calibradas, combinar un modelo de profundidad monocular y suavizar temporalmente. Un sensor estéreo, LiDAR o radar proporciona escala física mucho más fiable.

## 3. Velocidad

El seguimiento entre fotogramas permite estimar el cambio de distancia y, por tanto, velocidad relativa:

```text
velocidad_relativa ≈ -cambio_de_distancia / cambio_de_tiempo
velocidad_objetivo ≈ velocidad_del_teléfono + velocidad_relativa
```

Para velocidad absoluta hace falta la velocidad propia, obtenida por GPS o preferiblemente OBD-II/CAN, además de seguimiento persistente, marcas de tiempo, compensación del movimiento de la cámara y filtrado. Una sola cámara sin calibración no ofrece una medición legal ni suficientemente fiable para decisiones de conducción.

## 4. Funciones todavía experimentales para una versión futura

- Identificador persistente de cada vehículo mediante tracking.
- Asistente de calibración de cámara, altura e inclinación.
- Velocidad propia por GPS u OBD-II y cálculo de distancia de seguimiento.
- Registro de incertidumbre; no mostrar cifras cuando la calidad sea insuficiente.

Estas funciones deben considerarse experimentales y no sustituyen un sistema ADAS homologado ni la atención del conductor.
