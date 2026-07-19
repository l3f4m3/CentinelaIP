# Formato de modelos Centinela

La app importa y exporta un ZIP con este contenido:

```text
model.onnx
labels.txt
manifest.json   (opcional al importar)
```

`labels.txt` contiene una clase por línea en el orden de los identificadores del modelo. `manifest.json` puede incluir `name`; la app no confía en el nombre para validar el modelo.

Requisitos de compatibilidad:

- tensor de entrada `1 × 3 × 640 × 640`, RGB float;
- salida end-to-end `1 × N × 6`;
- cada fila de salida: `left, top, right, bottom, confidence, class_id`;
- coordenadas sobre la imagen letterbox de 640 × 640;
- operadores compatibles con ONNX Runtime Android 1.27.

Al importar, la app limita el tamaño, extrae solo los tres archivos esperados, crea una sesión ONNX y valida entrada/salida antes de activar el modelo. Un ONNX YOLO convencional previo a NMS con salida `1 × 84 × 8400` no es compatible sin exportarlo en modo end-to-end.
