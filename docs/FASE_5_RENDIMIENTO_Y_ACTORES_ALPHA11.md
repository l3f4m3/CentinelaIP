# Alpha11 — rendimiento móvil y actores compuestos

## Objetivo

Reducir la latencia de inferencia en Pixel sin cambiar el modelo YOLO26n y evitar que un ciclista se presente como dos actores independientes cuando el modelo detecta simultáneamente persona y bicicleta.

## Cambios

- ONNX Runtime intenta crear primero una sesión con XNNPACK.
- XNNPACK usa un pool interno de hasta cuatro hilos para no saturar la interfaz.
- Si el proveedor acelerado no puede registrar o cargar el modelo, la sesión vuelve automáticamente a ORT CPU.
- La fusión semántica se ejecuta después del tracking, por lo que conserva distancia, TTC, riesgo e identificador temporal.
- Se soportan las combinaciones:
  - persona + bicicleta → ciclista;
  - persona + motocicleta → motociclista.
- La asociación exige proximidad horizontal, contacto vertical plausible y solapamiento suficiente. Ante duda no fusiona.

## Criterios automáticos

1. Compilar contra la API XNNPACK incluida en `onnxruntime-android`.
2. Mantener fallback CPU funcional.
3. Fusionar una pareja persona/bicicleta geométricamente consistente.
4. No fusionar actores separados.
5. Conservar TTC y el riesgo más severo de los componentes.
6. Superar todas las pruebas previas.
7. Generar APK release firmada e íntegra.

## Prueba física

Comparar `alpha10` y `alpha11` con la misma escena, teléfono, temperatura inicial y fuente:

- latencia mediana después de 60 segundos;
- IA FPS;
- temperatura después de cinco minutos;
- estabilidad de IDs;
- etiqueta `ciclista` cuando persona y bicicleta forman un único actor;
- ausencia de fusiones entre personas y bicicletas alejadas.

## Limitaciones

La fusión no crea una bicicleta que YOLO no haya detectado. Si solo existe la caja de persona, seguirá apareciendo `persona`. La aceleración de XNNPACK depende de los operadores del modelo y debe medirse físicamente; la compilación por sí sola no prueba una mejora de rendimiento.
