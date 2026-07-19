# Encontrar y validar el flujo RTSP

## 1. Identifica la IP sin adivinar

Abre la administración de tu router y busca la cámara en la lista DHCP. También puede aparecer con un nombre genérico como `IPCAM`, `CAMERA`, `Tuya`, `SmartLife` o sin nombre.

No expongas el puerto 554 a Internet y no uses redirección de puertos. Para acceso remoto seguro, usa una VPN doméstica como WireGuard o Tailscale.

## 2. Verifica si RTSP está habilitado

Revisa en la app original opciones como **RTSP**, **ONVIF**, **NVR**, **NAS**, **LAN live view** o **Third-party integration**. Algunas cámaras lo deshabilitan de fábrica y otras no lo soportan.

## 3. Prueba fuera de la APK

En VLC usa **Medio → Abrir ubicación de red**. Con FFmpeg:

```bash
ffplay -rtsp_transport tcp "rtsp://USUARIO:CONTRASEÑA@192.168.1.50:554/stream1"
```

Si esto falla, el problema no está en Centinela IP: la IP, ruta, contraseña, códec o soporte RTSP es incorrecto.

## 4. Rutas frecuentes

| Familia habitual | Ruta que puedes probar |
|---|---|
| Genérica | `/stream1`, `/stream2`, `/11` |
| XM / ICSee | `/user=USUARIO&password=CLAVE&channel=1&stream=0.sdp` |
| Hikvision | `/Streaming/Channels/101` |
| Dahua / compatibles | `/cam/realmonitor?channel=1&subtype=0` |
| Reolink | `/h264Preview_01_main` |
| Algunas cámaras ONVIF | `/onvif1` |

Las rutas son candidatos, no estándares universales. Cambiar extensiones o insistir con combinaciones aleatorias no crea soporte RTSP.

## 5. Códec

Configura el stream principal o secundario como H.264. H.265/HEVC no está soportado por el módulo RTSP de Media3 usado en esta versión.

## Diagnóstico que falta de la cámara fotografiada

Para identificarla hace falta al menos uno de estos datos:

- foto nítida de la etiqueta, caja o manual;
- nombre de la app original usada para vincularla;
- fabricante/modelo mostrado por el router;
- captura de su pantalla de información o configuración LAN;
- dirección MAC (puede revelar el fabricante, pero no publiques la contraseña ni el QR completo).
