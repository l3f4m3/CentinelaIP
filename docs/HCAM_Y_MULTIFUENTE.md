# HCAM, UUID y múltiples fuentes

## Identificación investigada

Un identificador `HCAM…` es un UID de dispositivo, no una dirección IP ni una URL de video. El punto de acceso asociado suele anunciarse como `XIOTA-HCAM…` o una variante muy próxima. La app permite dejar el UUID vacío al buscar y lo completa desde el SSID conectado cuando Android lo hace visible. Las credenciales deben tomarse de la etiqueta o el manual del equipo y no se publican en el código.

El cliente TinyCam compatible contiene los componentes `IOTCAPIs` y `AVAPIs` de ThroughTek/Kalay. La documentación oficial de ThroughTek exige una `sdk licenseKey` y una `privateKey` vinculada al nombre de paquete para inicializar el enlace P2P. Por eso Centinela no puede ni debe copiar las bibliotecas o las claves de otra aplicación.

## Flujo implementado

1. El usuario registra el UID, SSID esperado y credenciales de la cámara.
2. Centinela abre el panel Wi‑Fi de Android; el usuario mantiene la conexión aunque no tenga Internet.
3. La búsqueda se enlaza explícitamente a la interfaz Wi‑Fi, incluso si Android conserva los datos móviles como ruta predeterminada.
4. Se obtiene la puerta de enlace del punto de acceso y se envía una sonda ONVIF WS‑Discovery.
5. Si hace falta, se escanea el segmento IPv4 local para puertos RTSP 554/8554.
6. Se envían peticiones RTSP `DESCRIBE` y se negocia autenticación Basic o Digest. Se prueban `/11`, `/12`, `/live0.264`, `/ucast/11`, `/stream1` y otras rutas conocidas.
7. Una respuesta SDP válida completa automáticamente la URL. Si el servidor rechaza las credenciales, la app conserva la ruta candidata y pide corregir la contraseña.
8. Si solo existe Kalay P2P, el diagnóstico lo muestra sin fingir una conexión RTSP inexistente y ofrece abrir X‑IOT CAM para configurar la cámara en el Wi‑Fi 2.4 GHz.

## Mosaico y detección

- Se guardan hasta seis fuentes RTSP/HCAM/teléfono.
- Todas las fuentes activas se reproducen simultáneamente en un mosaico; en horizontal o tablet se usan dos columnas.
- Cada fuente tiene conexión, captura, confianza, avisos y activación de IA independientes.
- Un único detector ONNX se comparte de forma serializada. El planificador toma fotogramas en turno rotativo para evitar ejecutar varias inferencias simultáneas y agotar RAM/CPU/GPU.
- Los eventos conservan el nombre de la fuente que produjo la detección y tienen enfriamiento independiente.

## Integración P2P futura

Para activar video remoto únicamente por UID se necesita obtener del fabricante o de ThroughTek:

- Kalay SDK Android actual y su licencia de redistribución.
- `sdk licenseKey` válida para el servicio P2P del dispositivo.
- `privateKey` autorizada para `com.fm.centinelaip`.
- Definición de los comandos IO de este firmware (inicio de video, calidad, audio, configuración Wi‑Fi y autenticación).

No debe usarse un SDK Kalay antiguo: versiones históricas tuvieron problemas de seguridad y la integración moderna debe habilitar AuthKey y DTLS cuando el dispositivo los soporte.
