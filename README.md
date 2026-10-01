# dronmxE 🇲🇽 · DJI RC 2 & DJI Fly Waypoint Mission Planner

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20DJI%20RC%202-007ACC.svg)](https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20DJI%20RC%202-007ACC.svg)](https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK)
[![DJI Support](https://img.shields.io/badge/DJI%20Fly-Waypoints%20WPML-2ea44f.svg)](https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK)
[![Developer](https://img.shields.io/badge/Author-Edgar-ff69b4.svg)](mailto:jhonson2490@gmail.com)
[![Country](https://img.shields.io/badge/Made%20in-Mexico%20%F0%9F%87%B2%F0%9F%87%BD-green.svg)](https://github.com/mapitas2490-svg)

**dronmxE** es una estación de planificación y generación de misiones fotogramétricas profesionales en formato nativo **DJI WPML (KMZ)**, diseñada específicamente para ejecutarse de forma 100% nativa y autónoma en el control remoto inteligente **DJI RC 2**, tablets Android y navegadores.

Permite generar planes de vuelo para fotogrametría 2D (ortomosaicos) y modelos 3D oblicuos, inyectándolos directamente en los slots de vuelo de **DJI Fly** tanto en la **Tarjeta MicroSD (SD)** como en la **Memoria Interna del control**.

---

## 🚀 Descarga Directa del APK

Puedes descargar el instalador compilado listo para transferir a tu control o dispositivo Android:
* 📦 **[Descargar dronmxE.apk (v1.0.0)](apk/dronmxE.apk)**

---

## ✨ Características Principales

### 1. 📂 Detección Directa de Slots de DJI Fly (Tarjeta SD & Memoria Interna)
* **Búsqueda e Inspección Real**: La aplicación escanea directamente las rutas de almacenamiento de DJI Fly:
  * `/storage/<UUID>/Android/data/dji.go.v5/files/waypoint/` (Tarjeta MicroSD)
  * `/sdcard/Android/data/dji.go.v5/files/waypoint/` (Memoria Interna)
* **Botón de Forzado de Lectura**: Cuenta con el botón `🔄 Forzar Lectura en Control RC 2` que limpia la memoria caché e inspecciona físicamente el sistema de archivos del control para verificar los slots reales en tiempo real.
* **Identificación Visual Clara**:
  * `Tarjeta SD (RC 2)` (Verde): Misiones alojadas en la tarjeta MicroSD instalada en el control.
  * `Memoria Interna (RC 2)` (Azul): Misiones en el almacenamiento interno del equipo.
* **Respaldo de Seguridad Automático**: Al sobreescribir un vuelo, el archivo original se respalda como `<GUID>.kmz.bak`.

### 2. 🚀 Inyección Directa en DJI Fly (Ubicado en la parte superior)
* Botón de acceso rápido `🚀 Inyectar Misión en DJI Fly` visible en la cabecera tanto del panel lateral como de la pantalla de exportación.
* Al pulsar inyectar, reemplaza el archivo KMZ en el slot seleccionado y actualiza la fecha de modificación para que DJI Fly lo detecte como la misión más reciente.

### 3. 🗺️ Modos de Levantamiento Fotogramétrico
* **Mapeo 2D (Cuadrícula / Ortomosaico)**:
  * Generación automática de líneas de vuelo en serpentina según el rumbo óptimo.
  * Cálculo dinámico de GSD (Ground Sampling Distance), distancia entre pasadas y velocidad de crucero.
* **Modelo 3D (Doble Cuadrícula Cruzada con Overhang / Borde Exterior)**:
  * Cuadrícula ortogonal doble para captura exhaustiva de fachadas y geometrías verticales.
  * En modo 3D, el área de vuelo se expande automáticamente (+25 a 35 m de buffer) más allá del polígono para garantizar que la cámara oblicua capture las paredes exteriores del objeto o predio.
  * Inclinación de gimbal personalizable (ej. -60° a -70°).

### 4. ✍️ Edición Táctil Fluida del Polígono
* Vértices numerados (1, 2, 3...) de 26px optimizados para la pantalla táctil del control DJI RC 2.
* Arrastre a 60 FPS sin destrucción de marcadores durante el movimiento.
* Puntos medios interactivos (`+`) para insertar vértices fácilmente y toque en vértice para eliminarlo.

### 5. 📷 Captura de Fotos por Waypoint (No por intervalo de tiempo)
* Cada waypoint del plan de vuelo contiene una acción de actuador `takePhoto` (`wpml:actionActuatorFunc = takePhoto`) disparada en el evento `reachPoint`.
* Evita fotos movidas por viento o variaciones de aceleración entre pasadas.

### 6. ⛰️ Altitud sobre el Suelo (AGL) y Modelos Digitales de Elevación (DEM)
* Descarga y muestreo automático de cotas topográficas SRTM / Open-Elevation.
* **Simbología Altimétrica Dinámica**: El trazado de la trayectoria en el mapa Leaflet se colorea en tiempo real con una escala de color hipsométrica (Azul para las cotas más bajas hasta Rojo para las cotas más altas).

### 7. 📁 Importación y Exportación Versátil
* **Carga de Archivos**: Admite polígonos en formato **KML**, **KMZ** y **GeoJSON**.
* **Opciones de Salida**:
  * **Sobrescribir Slot en DJI Fly**: Inyección directa en el control.
  * **Guardar en Descargas (.kmz)**: Archivo WPML listo para archivar o transportar.
  * **Compartir KMZ**: Envío inmediato mediante WhatsApp, Drive, Gmail o Bluetooth.

---

## 🛠️ Drones y Equipos Compatibles

Compatible con todos los drones DJI que admiten misiones de waypoints en **DJI Fly**:
* **DJI Mini 4 Pro**
* **DJI Mini 5 Pro**
* **DJI Air 3 & Air 3S**
* **DJI Mavic 3 / Mavic 3 Classic / Mavic 3 Pro**
* **DJI Mavic 4 Pro**
* Controladores: **DJI RC 2**, **DJI RC Pro**, smartphones y tablets Android.

---

## 📋 Guía de Instalación en el Control DJI RC 2

1. Copia el archivo [`dronmxE.apk`](apk/dronmxE.apk) a una tarjeta MicroSD o conéctalo por cable USB a la PC.
2. Inserta la MicroSD en el control **DJI RC 2**.
3. Abre un explorador de archivos o lanzador (ej. *ATV Launcher* o *Files*) en el control e instala el APK.
4. Concede los permisos de almacenamiento y ubicación solicitados.
5. Abre **dronmxE**, dibuja tu área o importa tu KML, configura los traslapes y pulsa **Inyectar Misión en DJI Fly**.
6. En tu dron/control, abre **DJI Fly** -> **Modo Trayectoria (Waypoints)** y carga la misión recién transferida.

---

## 🏗️ Estructura del Repositorio

```text
DronmxE_dji_ControlRC2_APK/
├── apk/
│   └── dronmxE.apk                 # APK compilado listo para instalar
├── app/
│   ├── build.gradle                # Configuración de compilación Android
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml # Permisos y configuración de red cleartext
│           ├── assets/
│           │   └── index.html      # Núcleo web interactivo (Leaflet, JSZip, Math)
│           ├── java/com/openflight/mission/
│           │   └── MainActivity.java # Puente nativo Java-Android (MTP, SD, Bridge)
│           └── res/                # Iconos, temas y definiciones XML
├── build.gradle
├── gradle.properties
├── settings.gradle
└── README.md
```

---

## 👤 Autor y Contacto

* **Desarrollador**: Edgar 🇲🇽
* **Email**: [jhonson2490@gmail.com](mailto:jhonson2490@gmail.com)
* **Repositorio**: [https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK](https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK)

---
*Desarrollado para la comunidad de topografía, fotogrametría y cartografía aérea con drones DJI.*
