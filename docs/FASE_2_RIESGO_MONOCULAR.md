# Fase 2A · Tracking y riesgo monocular experimental

## Alcance de `0.5.0-alpha06`

- Asociación temporal de detecciones por clase e IoU.
- Identificador persistente por objeto (`#ID`).
- Suavizado temporal de distancia y velocidad de cierre.
- Distancia aproximada por modelo pinhole y altura nominal de la clase.
- Tiempo hasta colisión (`TTC`) cuando existe velocidad de aproximación positiva.
- Clasificación visual `SAFE`, `ATTENTION`, `WARNING` y `CRITICAL`.
- Colores de riesgo aplicados directamente a las cajas.
- Reinicio automático del tracker por cambio de fuente, relación de aspecto o pausa prolongada.

## Clases con altura nominal inicial

Persona, bicicleta, automóvil, motocicleta, autobús, camión, perro, caballo, vaca, señal de pare y semáforo.

Las demás clases mantienen tracking e ID, pero no reciben distancia métrica.

## Limitaciones críticas

La distancia se calcula con un campo de visión vertical aproximado de 52° y alturas promedio. Por tanto:

- no es una medición certificada;
- cambia con lente, zoom, recorte, inclinación y geometría real del objeto;
- no es válida para frenar ni decidir maniobras;
- el TTC hereda el error de distancia y del intervalo entre inferencias;
- antes de alertas reales se requiere calibración intrínseca, validación con distancias conocidas y fusión temporal más robusta.

## Compuerta física

1. Confirmar que el mismo objeto conserva su `#ID` durante varios segundos.
2. Acercar y alejar el teléfono de un objeto estático y verificar el signo de la velocidad de cierre.
3. Comparar `~m` contra distancias medidas de 3, 5, 10 y 20 metros.
4. Verificar que objetos laterales no entren en riesgo crítico.
5. Registrar falsos TTC y saltos de ID.
6. No ejecutar la prueba conduciendo hasta completar primero las pruebas estáticas.
