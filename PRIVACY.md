# Privacidad

Centinela IP procesa el stream RTSP, la cámara del teléfono y la inferencia en el dispositivo. No incluye telemetría, anuncios, cuenta de usuario ni un servidor externo preconfigurado.

- Las URL RTSP, UUID y nombres de red esperados se guardan en preferencias privadas.
- Usuario y contraseña de cada fuente, además del token opcional de entrenamiento, se cifran con una clave AES no exportable de Android Keystore.
- El descubrimiento HCAM/ONVIF utiliza únicamente la red Wi‑Fi activa. No envía el UUID, las credenciales ni los resultados a Centinela ni a un servidor propio.
- Eventos, imágenes de entrenamiento, anotaciones y modelos importados se guardan en el espacio privado de la app.
- Las capturas manuales van a `Imágenes/CentinelaIP` solo cuando el usuario pulsa capturar.
- Las cajas rechazadas se excluyen del entrenamiento. Solo se borran definitivamente mediante confirmación o si el usuario activa **Eliminar baja confianza**.
- Un dataset sale del teléfono únicamente cuando el usuario inicia un entrenamiento hacia la URL que configuró. El servicio incluido no es una nube de terceros.
- Para un servidor en Internet se debe usar HTTPS, autenticación fuerte, firewall y límites de carga. Para una red local confiable puede usarse HTTP conscientemente.
- Desinstalar la app elimina configuración, modelos y datos privados; las capturas de la galería permanecen.

Las imágenes de cámaras pueden contener datos personales. El propietario debe definir base legal, avisos, acceso, retención y seguridad conforme a la normativa aplicable.
