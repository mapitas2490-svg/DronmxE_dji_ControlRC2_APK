# dronmxE 🇲🇽 · DJI RC 2 & DJI Fly Waypoint Mission Planner

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20DJI%20RC%202-007ACC.svg)](https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK)
[![DJI Support](https://img.shields.io/badge/DJI%20Fly-Waypoints%20WPML-2ea44f.svg)](https://flylitchi.com)
[![Developer](https://img.shields.io/badge/Author-Edgar-ff69b4.svg)](mailto:jhonson2490@gmail.com)
[![Country](https://img.shields.io/badge/Made%20in-Mexico%20%F0%9F%87%B2%F0%9F%87%BD-green.svg)](https://github.com/mapitas2490-svg)

**dronmxE** es una estación de planificación y generación de misiones fotogramétricas profesionales en formato nativo **DJI WPML (KMZ)**, diseñada específicamente para ejecutarse en el control remoto inteligente **DJI RC 2**, tablets Android y navegadores de escritorio.

Permite generar planes de vuelo para fotogrametría 2D (ortomosaicos) y modelos 3D oblicuos, inyectándolos directamente en los slots de vuelo de **DJI Fly** tanto en la **Tarjeta MicroSD (SD)** como en la **Memoria Interna**, o sincronizándolos en tiempo real a través de **Litchi Hub Bridge**.

---

## 🚀 Descarga Directa del APK

Puedes descargar el instalador compilado listo para transferir a tu control o dispositivo Android:
* 📦 **[Descargar dronmxE.apk (v1.0.0)](apk/dronmxE.apk)**

---

## ✨ Características Principales

### 1. 📂 Detección Inteligente de Slots de DJI Fly (Tarjeta SD & Memoria Interna)
* **Soporte Nativo para MicroSD**: Si tienes configurado el almacenamiento de tu DJI RC 2 en la **Tarjeta SD**, la aplicación detecta y lee automáticamente las misiones guardadas en `/storage/<UUID>/Android/data/dji.go.v5/files/waypoint/`.
* **Identificación Visual por Badges**:
  * `[SD Card]` (Verde): Misiones alojadas en la tarjeta MicroSD.
  * `[RC 2 · Litchi Bridge]` (Azul): Misiones detectadas en vivo mediante el puente USB de Windows.
  * `[Memoria Interna]` (Ámbar): Misiones en el almacenamiento interno del equipo.
* **Respaldo de Seguridad Automático**: Al sobreescribir un vuelo, el archivo original se respalda como `<GUID>.kmz.lchbak` para garantizar que nunca pierdas tus datos originales.

### 2. 🔌 Integración con Litchi Hub Bridge (USB Windows)
* Comunicación directa con el servicio local `http://127.0.0.1:17891/api/fly/flights` y sobreescritura multipart mediante `/api/fly/flights/overwrite`.
* Permite reemplazar vuelos desde la PC al control conectado por USB con un solo clic.

### 3. 🗺️ Modos de Levantamiento Fotogramétrico
* **Mapeo 2D (Cuadrícula / Ortomosaico)**:
  * Generación automática de líneas de vuelo en serpentina según el rumbo óptimo.
  * Cálculo dinámico de GSD (Ground Sampling Distance), distancia entre pasadas y velocidad de crucero.
* **Modelo 3D (Doble Cuadrícula Cruzada / Cross-Hatch)**:
  * Cuadrícula ortogonal doble para captura exhaustiva de fachadas y geometrías verticales.
  * Inclinación de gimbal personalizable (ej. -60° a -70°) para fotogrametría tridimensional.

### 4. 📷 Captura de Fotos por Waypoint (No por intervalo de tiempo)
* Cada waypoint del plan de vuelo contiene una acción de actuador `takePhoto` (`wpml:actionActuatorFunc = takePhoto`) disparada en el evento `reachPoint`.
* Evita fotos movidas por viento o variaciones de aceleración entre pasadas.

### 5. ⛰️ Altitud sobre el Suelo (AGL) y Modelos Digitales de Elevación (DEM)
* Descarga y muestreo automático de cotas topográficas SRTM / Open-Elevation.
* **Simbología Altimétrica Dinámica**: El trazado de la trayectoria en el mapa Leaflet se colorea en tiempo real con una escala de color hipsométrica (Azul para las cotas más bajas hasta Rojo para las cotas más altas).

### 6. 📁 Importación y Exportación Versátil
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
5. Abre **dronmxE**, dibuja tu área o importa tu KML, configura los traslapes y pulsa **Exportar**.

---

## 💻 Guía de Uso con Litchi Hub Bridge (PC Windows)

1. En tu PC con Windows, asegúrate de que **Litchi Hub Bridge** esté ejecutándose en la bandeja del sistema.
2. Conecta el control **DJI RC 2** a la computadora mediante cable USB.
3. Desbloquea la pantalla del control y selecciona el modo **Transferencia de archivos (MTP)**.
4. Abre **dronmxE**: verás automáticamente la lista de vuelos cargados en el control.
5. Selecciona el slot que deseas reemplazar y haz clic en **Sobrescribir Slot en DJI Fly**.
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
