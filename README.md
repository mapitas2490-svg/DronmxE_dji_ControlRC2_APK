# dronmxE — Versión 2.0 (DJI RC 2 MTP Bridge)

[![Version](https://img.shields.io/badge/version-2.0-blue.svg)](https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK)
[![DJI RC 2](https://img.shields.io/badge/DJI-RC%202%20Compatible-green.svg)](https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK)
[![WPML](https://img.shields.io/badge/WPML-1.0.6%20Native-orange.svg)](https://github.com/mapitas2490-svg/DronmxE_dji_ControlRC2_APK)

Aplicación Android para la transferencia directa, sobreescritura y creación de misiones de vuelo KML/KMZ (DJI WPML 1.0.6) en el control remoto **DJI RC 2 / RC Pro** vía cable USB OTG (MTP).

---

## 🚀 Novedades de la Versión 2.0
1. **Detección y Visualización RC 2 en Tiempo Real**: Escaneo bidireccional estable de ranuras DJI Fly con lectura instantánea de waypoints y renderizado de trayectorias en miniaturas.
2. **Corrección de Selección Local**: Selección estable de cualquier misión local sin regresión al archivo de 541 WP.
3. **Compatibilidad Total WPML 1.0.6**: Inyección de metadatos `<Folder>` y `<wpml:missionName>` para reconocimiento inmediato en el planificador de DJI Fly.
4. **Sincronización Dual MTP**: Inyección en Memoria Interna y Tarjeta SD del RC 2.

Consulte la [DOCUMENTACION_V2.md](DOCUMENTACION_V2.md) para detalles técnicos completos y arquitectura.

---

## 📱 APKs de Instalación
Ubicación de los binarios compilados listos para instalar:
- `F:\DJI_3S_AIR\apk\dronmxE.apk` (v2.0)
- `F:\DJI_3S_AIR\apk\dronmxe_mtp_bridge.apk` (v2.0)

---

## 🔄 Punto de Restauración (GitHub)
Si requiere volver a esta versión estable en cualquier momento:
```bash
git checkout v2.0
```
