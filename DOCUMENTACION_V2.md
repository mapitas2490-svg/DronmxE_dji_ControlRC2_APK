# dronmxE — Versión 2.0 (DJI RC 2 MTP Bridge)

Sistema profesional para la transferencia, gestión, visualización y sobreescritura bidireccional de misiones de vuelo (KML / KMZ / WPML 1.0.6) directamente hacia y desde el control remoto **DJI RC 2** (DJI Fly) mediante protocolo USB MTP (Host OTG).

---

## 📌 1. Arquitectura del Sistema

El proyecto consta de dos implementaciones sincronizadas al 100%:
- **`OpenFlightMissionApp`**: Repositorio principal versionado en Git/GitHub con soporte completo y empaquetador APK.
- **`DjiMtpBridgeApp`**: Espejo modular independiente para compilación directa del puente MTP.

### Componentes Clave:

1. **`MainActivity.java`**:
   - **WebView Bridge (`AndroidBridge`)**: Expone métodos asíncronos hacia la interfaz JavaScript (`getDeviceSlotsJson`, `getLocalMissionsJson`, `overwriteSlotFromLocal`, `createNewSlotFromLocal`, `deleteDeviceSlot`, `downloadDeviceSlot`).
   - **Gestión de permisos**: Administra almacenamiento externo en Android (`READ_MEDIA_IMAGES`, `MANAGE_EXTERNAL_STORAGE` y selector `ACTION_OPEN_DOCUMENT`).
   - **Ciclo de vida**: Gestiona conexión/desconexión USB y comunicación reactiva con la UI vía `notifyJs`.

2. **`MtpHelper.java`**:
   - **Detección MTP inteligente**: Identifica dispositivos USB Android y DJI (Vendor ID `0x2CA3`, `0x18D1`, etc.) sin reiniciar destructivamente el bus USB.
   - **Escaneo MTP dual**: Detecta y prioriza tanto la **Memoria Interna Compartida** como la **Tarjeta MicroSD** del control RC 2.
   - **Rutas Waypoint DJI Fly**: Busca de forma recursiva e insensible a mayúsculas/minúsculas la ruta:
     `Android/data/dji.go.v5/files/Waypoint` (o la ruta personalizada definida por el usuario).
   - **Protección de concurrencia**: Bloque `try ... finally { isScanning = false; }` que garantiza que el hilo de escaneo jamás quede bloqueado.
   - **Inyección y Limpieza**: Escribe el paquete nativo nombrado estrictamente como `<GUID>.kmz` y elimina imágenes obsoletas en la carpeta de la ranura para que DJI Fly reindexe y regenere la miniatura.
   - **Parser ultrarrápido (`parseKmzFast`)**: Lee en memoria los archivos `waylines.wpml` y `template.kml` sin expresiones regulares recursivas pesadas, extrayendo coordenadas y conteo de waypoints al instante.

3. **`WpmlKmzBuilder.java`**:
   - Genera paquetes KMZ bajo el estándar estricto **DJI WPML 1.0.6**:
     - Estructura: `wpmz/template.kml` y `wpmz/waylines.wpml`.
     - Inyecta metadatos del dron (`droneEnumValue`, `droneSubEnumValue`).
     - Inserta etiquetas `<Folder>` con `<wpml:templateType>waypoint</wpml:templateType>` y `<wpml:missionName><![CDATA[...]]></wpml:missionName>`.
     - Preserva la geometría, altitudes, velocidades y acciones de cámara originales.

4. **`index.html` (Interfaz de Usuario)**:
   - Diseño moderno Dark Mode optimizado para campo y tabletas/móviles.
   - Miniaturas dinámicas en `<canvas>` dibujadas a escala real con los puntos de vuelo (verde para misiones locales, azul cian para ranuras del RC 2).
   - Selección persistente: al seleccionar una misión local (ej. 73 WP), la selección se fija y no salta a la primera misión.
   - Actualización en vivo de botones:
     - `⚡ SOBREESCRIBIR EN RC 2`: Sobreescribe la ranura seleccionada manteniendo el GUID.
     - `➕ CREAR NUEVA MISIÓN EN RC 2`: Genera un nuevo GUID único e inyecta la misión en el RC 2.

---

## ⚙️ 2. Requisitos Indispensables para su Funcionamiento (v2.0)

Para que la comunicación MTP con el control RC 2 opere sin inconvenientes:

1. **Hardware**:
   - Teléfono o tableta Android con soporte **USB OTG**.
   - Cable USB-C a USB-C de datos (o adaptador OTG conectado al teléfono).
   - Conectar al puerto **HOST** del control DJI RC 2.
2. **Estado del Control DJI RC 2**:
   - **Pantalla desbloqueada**: El RC 2 no debe estar bloqueado ni con la pantalla suspendida durante la sincronización inicial.
   - **Modo USB**: Al conectar el cable, en el menú emergente del RC 2 se debe seleccionar **"Transferencia de Archivos"** (MTP). Si queda en *"Solo Carga"*, Android no expone el sistema de archivos y la app avisará `[!] RC 2 detectado pero bloqueado`.
3. **Permiso USB en la App**:
   - Aceptar el cuadro de diálogo de Android: *"¿Permitir que dronmxE acceda a DJI RC 2?"* (marcar siempre por defecto).
4. **Refresco en DJI Fly (Importante)**:
   - Debido al sistema de caché de DJI Fly en el RC 2, tras inyectar o sobreescribir una misión:
     > **Deslizar hacia arriba en el RC 2 para abrir las aplicaciones recientes, cerrar DJI Fly y volver a abrirlo.**
     Esto fuerza a DJI Fly a releer el directorio de Waypoints y mostrar el nuevo nombre y trayectoria.

---

## 📜 3. Historial de Versiones y Punto de Restauración

### Versión 2.0 (Checkpoint Estable - Octubre 2026)
- **Corrección de Reseteo Local**: Se eliminó la condición estricta de tamaño en `renderLocalMissions()`, evitando que la selección vuelva automáticamente a `Casa.kmz` (541 WP) al seleccionar otra misión.
- **Visualización y Detección de Ranuras RC 2**: Implementado desbloqueo de escaneo (`finally { isScanning = false; }`) y parser no bloqueante de coordenadas.
- **Empaquetado Nativo DJI Fly WPML 1.0.6**: Inclusión de `<Folder>` y `<wpml:missionName>` en `template.kml` para compatibilidad completa con el editor de misiones del RC 2.
- **Dual-Storage Write**: Escritura simultánea y transparente tanto en la Memoria Interna como en la Tarjeta SD del RC 2.
- **Ruta de Despliegue de APKs**: Generación automatizada de APKs en `F:\DJI_3S_AIR\apk\`:
  - `dronmxE.apk` (Versión 2.0)
  - `dronmxe_mtp_bridge.apk` (Versión 2.0)

---

## 🛠️ 4. Procedimiento de Restauración desde GitHub

Si en el futuro se produce algún fallo o se desea volver a este estado exacto y 100% funcional:

```bash
# 1. Clonar el repositorio
git clone https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK.git

# 2. Restaurar al tag de la Versión 2.0
git checkout v2.0

# 3. Compilar los APKs con Gradle
./gradlew assembleDebug

# 4. Los APKs quedarán generados en:
# app/build/outputs/apk/debug/app-debug.apk
```
