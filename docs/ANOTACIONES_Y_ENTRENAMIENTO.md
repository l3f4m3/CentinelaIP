# Anotaciones y autoaprendizaje

## Crear anotaciones precisas

1. Abre **Ajustes → Anotar** y selecciona una imagen o video.
2. En video usa la barra temporal y `−1/+1` para avanzar por fotogramas.
3. Dibuja una caja o pulsa **Sugerir con IA**.
4. La etiqueta inicial puede ser `placa` o una clase del modelo. El campo siempre es editable.
5. Arrastra la caja o cualquiera de sus cuatro esquinas. Usa zoom/paneo para objetos pequeños.
6. En **Editar caja** introduce límites exactos en porcentaje si necesitas precisión reproducible.
7. Acepta, rechaza o elimina cada caja y guarda la muestra.

Colores/estados:

- Verde: manual y aceptada.
- Azul: sugerencia automática aceptada.
- Amarillo: requiere revisión.
- Rojo: rechazada; no se exporta para entrenar.

La cola **Revisar dataset** permite reabrir cualquier muestra. La eliminación de rechazadas es explícita y confirmada.

## Dataset YOLO

La exportación contiene imágenes/etiquetas `train` y `val`, `data.yaml`, `classes.txt`, el índice completo y un manifiesto. Con varias fuentes se separan fuentes completas. Con un único video se reserva un bloque temporal final para validación, evitando repartir fotogramas vecinos entre ambos conjuntos. Se exigen al menos dos muestras aceptadas.

## Entrenar desde el teléfono

La pantalla **Entrenar** exporta el dataset, lo sube al endpoint configurado, crea el trabajo, consulta el progreso y descarga/valida/activa el mejor ONNX. El servicio de referencia está en `trainer-server/`.

El ajuste completo de un detector YOLO26 no se ejecuta dentro del APK: requiere artefactos entrenables y recursos de cómputo que un ONNX de inferencia no contiene. El teléfono funciona como consola y propietario del dataset; el cálculo pesado ocurre en el servidor elegido por el usuario.

## Autoaprendizaje seguro

Después de cada ronda, el mejor modelo puede reanalizar todas las cajas automáticas. La calidad combina confianza nueva y solapamiento IoU con la caja existente:

- por encima del umbral alto: aceptada;
- entre ambos umbrales: revisar;
- por debajo del umbral bajo: rechazada y excluida;
- las manuales se conservan siempre;
- duplicados de la misma clase con IoU mayor a 0,90 dejan solo el mejor.

**Eliminar baja confianza** está desactivado por defecto. Al activarlo se borran las cajas rechazadas después de la reevaluación; si una imagen queda sin cajas, se retira esa muestra.
