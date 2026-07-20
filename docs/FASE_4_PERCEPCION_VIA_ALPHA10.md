# Fase 4 · Percepción de vía alpha10

## Evidencia física que originó el incremento
La captura de prueba confirmó:
- pantalla completa correcta;
- detecciones alineadas;
- 159 ms por inferencia, 2,0 IA FPS y 39,2 °C con estado térmico leve;
- bordes blancos del andén interpretados como carril con confianza excesiva;
- guía manual y vía automática dibujadas simultáneamente;
- alertas críticas producidas por cercanía aislada.

## Implementado
- Clasificación contextual entre demarcación pintada y límite físico de la vía.
- Bordes físicos dibujados en cian y demarcaciones en blanco o amarillo.
- Confianza máxima heurística limitada a 86 % para marcas y 68 % para bordes.
- Validación de convergencia, anchura y punto de fuga antes de aceptar un corredor.
- Etiqueta diferenciada: `CARRIL`, `BORDES` o `LÍNEA`.
- Badge con fondo para evitar superposición de textos.
- La guía manual se oculta cuando existe un corredor automático estable; reaparece al ajustar con dos dedos.
- TTC y velocidad de cierre se publican solo después de cuatro observaciones estables.
- La cercanía por sí sola ya no genera una alerta crítica; se exige TTC corto, distancia extrema o aproximación consistente.

## Validación automática
- 50 pruebas unitarias aprobadas.
- Compilación APK release aprobada.
- Integridad ZIP aprobada.
- Firma APK aprobada.
- SHA-256 verificado.

## Validación física pendiente
- Repetir la escena de la calle estrecha.
- Confirmar que los andenes se presenten como `BORDES`, no como `CARRIL`.
- Confirmar que la confianza no supere 68 % para bordes.
- Confirmar que el HUD manual desaparezca cuando se detecte un corredor automático.
- Confirmar que un objetivo a ~4 m con TTC largo sea naranja o amarillo, no rojo.

## Limitación
La percepción de vía continúa siendo heurística. No sustituye segmentación semántica ni permite asumir trayectoria segura.