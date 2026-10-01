package com.dronmxe.mtpbridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.mtp.MtpConstants;
import android.mtp.MtpDevice;
import android.mtp.MtpObjectInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MtpHelper {

    private static final String ACTION_USB_PERMISSION = "com.dronmxe.mtpbridge.USB_PERMISSION";
    public static final int DJI_VENDOR_ID = 0x2CA3; // 11427
    public static final int DJI_PRODUCT_ID = 0x1021; // 4129

    public interface LogCallback {
        void onLog(String message);
        void onDeviceStatus(boolean connected, String description);
    }

    private final Context context;
    private final UsbManager usbManager;
    private final LogCallback callback;
    private UsbDevice currentDevice;
    private MtpDevice mtpDevice;

    // Candidate directory paths for DJI Fly waypoint missions across different versions & storage
    private static final String[][] WAYPOINT_PATH_CANDIDATES = new String[][]{
            {"Android", "data", "dji.go.v5", "files", "waypoint"},
            {"Android", "data", "dji.go.v5", "files", "waylines"},
            {"Android", "data", "dji.go.v5", "files", "waypoint_v2"},
            {"Android", "data", "dji.go.v5", "files", "DJI", "waypoint"},
            {"DJI", "dji.go.v5", "files", "waypoint"},
            {"DJI", "waypoint"},
            {"dji.go.v5", "files", "waypoint"},
            {"Download"}
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            callback.onLog("[OK] Permiso USB concedido por el usuario.");
                            openMtpDevice(device);
                        }
                    } else {
                        callback.onLog("[ERROR] Permiso USB denegado para el control RC 2.");
                        callback.onDeviceStatus(false, "Permiso denegado");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                callback.onLog("[INFO] Dispositivo USB conectado.");
                findAndConnectDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                callback.onLog("[WARN] Dispositivo USB desconectado.");
                close();
                callback.onDeviceStatus(false, "Desconectado");
            }
        }
    };

    public MtpHelper(Context context, LogCallback callback) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.callback = callback;

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
    }

    public void unregister() {
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        close();
    }

    public void close() {
        if (mtpDevice != null) {
            try {
                mtpDevice.close();
            } catch (Exception ignored) {}
            mtpDevice = null;
        }
        currentDevice = null;
    }

    public void findAndConnectDevice() {
        close();
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            callback.onLog("[INFO] No se detecta ningún dispositivo USB OTG conectado.");
            callback.onDeviceStatus(false, "Sin cable OTG conectado");
            return;
        }

        UsbDevice targetDevice = null;
        for (UsbDevice dev : deviceList.values()) {
            callback.onLog(String.format("[SCAN] USB VID: 0x%04X, PID: 0x%04X - %s",
                    dev.getVendorId(), dev.getProductId(), dev.getDeviceName()));
            if (dev.getVendorId() == DJI_VENDOR_ID) {
                targetDevice = dev;
                callback.onLog("[OK] ¡Control DJI RC 2 detectado!");
                break;
            }
        }

        if (targetDevice == null) {
            for (UsbDevice dev : deviceList.values()) {
                if (dev.getDeviceClass() == UsbConstants.USB_CLASS_STILL_IMAGE || dev.getDeviceClass() == 0) {
                    targetDevice = dev;
                    callback.onLog("[INFO] Seleccionando dispositivo MTP candidato.");
                    break;
                }
            }
        }

        if (targetDevice == null) {
            callback.onLog("[AVISO] No se encontró control DJI. Conecta el cable USB OTG.");
            callback.onDeviceStatus(false, "Dispositivo no reconocido");
            return;
        }

        currentDevice = targetDevice;
        if (usbManager.hasPermission(targetDevice)) {
            callback.onLog("[OK] Permiso USB ya activo.");
            openMtpDevice(targetDevice);
        } else {
            callback.onLog("[INFO] Solicitando permiso de acceso al control...");
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(targetDevice, pi);
        }
    }

    private void openMtpDevice(UsbDevice device) {
        try {
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                callback.onLog("[ERROR] No se pudo abrir conexión USB (openDevice retornó null).");
                callback.onDeviceStatus(false, "Fallo al abrir conexión USB");
                return;
            }

            mtpDevice = new MtpDevice(device);
            boolean opened = mtpDevice.open(connection);
            if (!opened) {
                callback.onLog("[ERROR] MtpDevice.open() falló. Revisa si el RC 2 está desbloqueado.");
                callback.onDeviceStatus(false, "MTP rechazada por RC 2");
                return;
            }

            int[] storageIds = mtpDevice.getStorageIds();
            if (storageIds == null || storageIds.length == 0) {
                callback.onLog("[WARN] MTP abierto pero no reporta almacenes activos. Desbloquea la pantalla del RC 2.");
                callback.onDeviceStatus(true, "RC 2 conectado (pantalla bloqueada)");
                return;
            }

            callback.onLog("[OK] ¡MTP conectado con éxito! Almacenes disponibles: " + storageIds.length);
            callback.onDeviceStatus(true, "DJI RC 2 Conectado y Listo");
        } catch (Exception e) {
            callback.onLog("[ERROR] Excepción abriendo MTP: " + e.getMessage());
            callback.onDeviceStatus(false, "Error: " + e.getMessage());
        }
    }

    public static class DeviceSlotInfo {
        public String guid = "";
        public String displayName = "";
        public long dateModified = 0;
        public long size = 0;
        public int kmzHandle = -1;
        public int folderHandle = -1;
        public int storageId = 0;
        public int wpCount = 0;
        public String base64 = "";
    }

    private static class KmzParsedInfo {
        int wpCount = 0;
        String missionName = "";
        long updateTime = 0;
    }

    private KmzParsedInfo parseXmlContent(String xml) {
        KmzParsedInfo info = new KmzParsedInfo();
        if (xml == null || xml.isEmpty()) return info;

        // Extract updateTime
        java.util.regex.Pattern pUt = java.util.regex.Pattern.compile("<wpml:updateTime>\\s*(\\d+)\\s*</wpml:updateTime>", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher mUt = pUt.matcher(xml);
        if (mUt.find()) {
            try {
                long ut = Long.parseLong(mUt.group(1));
                if (ut > 0) info.updateTime = ut;
            } catch (Exception ignored) {}
        }

        // Extract missionName from <wpml:missionName>
        java.util.regex.Pattern pNm = java.util.regex.Pattern.compile("<wpml:missionName>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</wpml:missionName>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher mNm = pNm.matcher(xml);
        if (mNm.find()) {
            String nameStr = mNm.group(1).trim();
            if (!nameStr.isEmpty() && !nameStr.toLowerCase().endsWith(".kml") && !nameStr.toLowerCase().endsWith(".wpml")) {
                info.missionName = nameStr;
            }
        }

        // Fallback to <name>
        if (info.missionName.isEmpty()) {
            java.util.regex.Pattern pNm2 = java.util.regex.Pattern.compile("<name>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</name>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher mNm2 = pNm2.matcher(xml);
            while (mNm2.find()) {
                String nameStr = mNm2.group(1).trim();
                if (!nameStr.isEmpty() && !nameStr.toLowerCase().endsWith(".kml") && !nameStr.toLowerCase().endsWith(".wpml") && !nameStr.equalsIgnoreCase("wpmz") && !nameStr.equalsIgnoreCase("waylines") && !nameStr.equalsIgnoreCase("waypoint")) {
                    info.missionName = nameStr;
                    break;
                }
            }
        }

        // Count Placemarks, wpml:waypoint, wpml:executeHeight, coordinates
        int pmCount = 0, idx = 0;
        while ((idx = xml.indexOf("<Placemark", idx)) != -1) { pmCount++; idx += 10; }
        
        int wpmlWpCount = 0; idx = 0;
        while ((idx = xml.indexOf("<wpml:waypoint", idx)) != -1) { wpmlWpCount++; idx += 14; }

        int hCount = 0; idx = 0;
        while ((idx = xml.indexOf("<wpml:executeHeight", idx)) != -1) { hCount++; idx += 19; }

        int coordCount = 0;
        java.util.regex.Pattern pCoord = java.util.regex.Pattern.compile("<coordinates>([\\s\\S]*?)</coordinates>", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher mCoord = pCoord.matcher(xml);
        while (mCoord.find()) {
            String[] pts = mCoord.group(1).trim().split("\\s+");
            for (String p : pts) {
                if (p.contains(",")) coordCount++;
            }
        }

        info.wpCount = Math.max(Math.max(pmCount, wpmlWpCount), Math.max(hCount, coordCount));
        return info;
    }

    private KmzParsedInfo parseKmzInfo(byte[] kmzData) {
        if (kmzData == null || kmzData.length == 0) return new KmzParsedInfo();

        // First check if raw XML string
        try {
            String headerStr = new String(kmzData, 0, Math.min(kmzData.length, 256), "UTF-8");
            if (headerStr.contains("<?xml") || headerStr.contains("<kml") || headerStr.contains("<wpml")) {
                return parseXmlContent(new String(kmzData, "UTF-8"));
            }
        } catch (Exception ignored) {}

        KmzParsedInfo info = new KmzParsedInfo();
        try {
            ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(kmzData));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName().toLowerCase();
                if (entryName.endsWith(".wpml") || entryName.endsWith(".kml")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = zis.read(buf)) != -1) baos.write(buf, 0, r);
                    String xml = baos.toString("UTF-8");
                    KmzParsedInfo sub = parseXmlContent(xml);
                    if (sub.wpCount > 0) info.wpCount = Math.max(info.wpCount, sub.wpCount);
                    if (sub.updateTime > 0 && info.updateTime == 0) info.updateTime = sub.updateTime;
                    if (sub.missionName != null && !sub.missionName.isEmpty() && info.missionName.isEmpty()) {
                        info.missionName = sub.missionName;
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception ignored) {}
        return info;
    }

    private int findHandleForPath(int storageId, String[] pathParts) {
        if (mtpDevice == null) return -1;
        int currentHandle = 0;
        for (String part : pathParts) {
            int foundHandle = -1;
            int[] children = mtpDevice.getObjectHandles(storageId, 0, currentHandle);
            if (children == null) return -1;
            for (int handle : children) {
                MtpObjectInfo info = mtpDevice.getObjectInfo(handle);
                if (info != null && part.equalsIgnoreCase(info.getName())) {
                    foundHandle = handle;
                    break;
                }
            }
            if (foundHandle == -1) return -1;
            currentHandle = foundHandle;
        }
        return currentHandle;
    }

    /** Deep scan across ALL storage volumes (Internal & SD Card) and ALL potential DJI Fly waypoint paths. */
    public List<DeviceSlotInfo> getDeviceSlotsDetailed() {
        List<DeviceSlotInfo> slots = new ArrayList<>();
        if (mtpDevice == null) {
            callback.onLog("[ERROR] MTP no está conectado.");
            return slots;
        }

        try {
            int[] storageIds = mtpDevice.getStorageIds();
            if (storageIds == null || storageIds.length == 0) {
                callback.onLog("[WARN] No se detectaron volúmenes de almacenamiento en RC 2.");
                return slots;
            }

            Set<String> seenGuids = new HashSet<>();

            for (int storageId : storageIds) {
                callback.onLog(String.format("[DEEP SCAN] Escaneando almacenamiento MTP 0x%08X...", storageId));

                for (String[] pathParts : WAYPOINT_PATH_CANDIDATES) {
                    int parentHandle = findHandleForPath(storageId, pathParts);
                    if (parentHandle == -1) continue;

                    String fullPathStr = String.join("/", pathParts);
                    int[] items = mtpDevice.getObjectHandles(storageId, 0, parentHandle);
                    if (items == null || items.length == 0) continue;

                    for (int handle : items) {
                        MtpObjectInfo info = mtpDevice.getObjectInfo(handle);
                        if (info == null) continue;

                        String itemName = info.getName();
                        if (itemName == null || itemName.isEmpty()) continue;

                        // Case A: Item is a directory (GUID folder or mission folder)
                        boolean isFolder = (info.getFormat() == MtpConstants.FORMAT_ASSOCIATION || info.getFormat() == 0);
                        if (isFolder) {
                            String guid = itemName;
                            if (seenGuids.contains(guid.toLowerCase())) continue;

                            int[] subFiles = mtpDevice.getObjectHandles(storageId, 0, handle);
                            if (subFiles == null || subFiles.length == 0) continue;

                            DeviceSlotInfo slot = new DeviceSlotInfo();
                            slot.guid = guid;
                            slot.folderHandle = handle;
                            slot.storageId = storageId;
                            slot.dateModified = info.getDateModified() > 0 ? (info.getDateModified() * 1000L) : System.currentTimeMillis();

                            for (int fh : subFiles) {
                                MtpObjectInfo fi = mtpDevice.getObjectInfo(fh);
                                if (fi == null) continue;

                                String fname = fi.getName() != null ? fi.getName().toLowerCase() : "";
                                if (fname.endsWith(".kmz") || fname.endsWith(".kml") || fname.endsWith(".wpml")) {
                                    slot.kmzHandle = fh;
                                    slot.size = fi.getCompressedSize();
                                    if (fi.getDateModified() > 0) {
                                        slot.dateModified = fi.getDateModified() * 1000L;
                                    }

                                    // Read byte content for WP & mission name extraction (limit 15MB)
                                    long sizeToRead = slot.size > 0 ? slot.size : 524288;
                                    if (sizeToRead < 15000000) {
                                        try {
                                            byte[] data = mtpDevice.getObject(fh, (int) sizeToRead);
                                            if (data != null && data.length > 0) {
                                                KmzParsedInfo parsed = parseKmzInfo(data);
                                                slot.wpCount = parsed.wpCount;
                                                if (parsed.updateTime > 0) {
                                                    slot.dateModified = parsed.updateTime;
                                                }
                                                if (parsed.missionName != null && !parsed.missionName.isEmpty()) {
                                                    slot.displayName = parsed.missionName;
                                                }
                                                if (data.length < 3500000) {
                                                    slot.base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                                                }
                                            }
                                        } catch (Exception ex) {
                                            callback.onLog("[WARN] No se pudo parsear " + fname + ": " + ex.getMessage());
                                        }
                                    }
                                    if (fname.endsWith(".kmz")) break;
                                }
                            }

                            if (slot.kmzHandle != -1 || slot.wpCount > 0) {
                                seenGuids.add(guid.toLowerCase());
                                if (slot.displayName.isEmpty()) slot.displayName = slot.guid;
                                slots.add(slot);
                                callback.onLog(String.format("[MISION RC2] %s | Ruta: %s | WP: %d | Tamaño: %d KB",
                                        slot.displayName, fullPathStr, slot.wpCount, slot.size / 1024));
                            }
                        }
                        // Case B: Item is a direct .kmz file in waypoint folder
                        else if (itemName.toLowerCase().endsWith(".kmz")) {
                            String guid = itemName.substring(0, itemName.length() - 4);
                            if (seenGuids.contains(guid.toLowerCase())) continue;

                            DeviceSlotInfo slot = new DeviceSlotInfo();
                            slot.guid = guid;
                            slot.displayName = guid;
                            slot.kmzHandle = handle;
                            slot.folderHandle = parentHandle;
                            slot.storageId = storageId;
                            slot.size = info.getCompressedSize();
                            slot.dateModified = info.getDateModified() > 0 ? (info.getDateModified() * 1000L) : System.currentTimeMillis();

                            long sizeToRead = slot.size > 0 ? slot.size : 524288;
                            if (sizeToRead < 15000000) {
                                try {
                                    byte[] data = mtpDevice.getObject(handle, (int) sizeToRead);
                                    if (data != null && data.length > 0) {
                                        KmzParsedInfo parsed = parseKmzInfo(data);
                                        slot.wpCount = parsed.wpCount;
                                        if (parsed.updateTime > 0) slot.dateModified = parsed.updateTime;
                                        if (parsed.missionName != null && !parsed.missionName.isEmpty()) {
                                            slot.displayName = parsed.missionName;
                                        }
                                        if (data.length < 3500000) {
                                            slot.base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                            seenGuids.add(guid.toLowerCase());
                            slots.add(slot);
                            callback.onLog(String.format("[KMZ DIRECTO RC2] %s | Ruta: %s | WP: %d",
                                    slot.displayName, fullPathStr, slot.wpCount));
                        }
                    }
                }
            }

            Collections.sort(slots, (a, b) -> Long.compare(b.dateModified, a.dateModified));
            callback.onLog("[OK] Misiones reales encontradas en RC 2: " + slots.size());
        } catch (Exception e) {
            callback.onLog("[ERROR] Excepción escaneando misiones profundamente: " + e.getMessage());
        }
        return slots;
    }

    public static class RemoteKmzFile {
        public String name = "";
        public long size = 0;
        public long dateModified = 0;
        public int handle = -1;
        public String folderTag = "RC2-Download";
    }

    public List<RemoteKmzFile> scanDownloadKmzFiles() {
        List<RemoteKmzFile> result = new ArrayList<>();
        if (mtpDevice == null) return result;
        try {
            int[] storageIds = mtpDevice.getStorageIds();
            if (storageIds == null || storageIds.length == 0) return result;

            for (int storageId : storageIds) {
                int downloadHandle = findHandleForPath(storageId, new String[]{"Download"});
                if (downloadHandle == -1) continue;

                int[] files = mtpDevice.getObjectHandles(storageId, 0, downloadHandle);
                if (files != null) {
                    for (int fh : files) {
                        MtpObjectInfo fi = mtpDevice.getObjectInfo(fh);
                        if (fi != null && fi.getName() != null && fi.getName().toLowerCase().endsWith(".kmz")) {
                            RemoteKmzFile rk = new RemoteKmzFile();
                            rk.name = fi.getName();
                            rk.size = fi.getCompressedSize();
                            rk.dateModified = fi.getDateModified() > 0 ? (fi.getDateModified() * 1000L) : System.currentTimeMillis();
                            rk.handle = fh;
                            rk.folderTag = "RC2-Download";
                            result.add(rk);
                        }
                    }
                }
            }
            callback.onLog("[OK] KMZ en carpeta Download del RC2: " + result.size());
        } catch (Exception e) {
            callback.onLog("[WARN] No se pudo escanear Download del RC2: " + e.getMessage());
        }
        return result;
    }

    public String readSlotKmzBase64(int kmzHandle, int size) {
        if (mtpDevice == null || kmzHandle <= 0) return "";
        try {
            if (size <= 0) {
                MtpObjectInfo info = mtpDevice.getObjectInfo(kmzHandle);
                if (info != null) size = info.getCompressedSize();
            }
            if (size <= 0) size = 524288;
            if (size < 15000000) { // Limit to 15MB
                byte[] data = mtpDevice.getObject(kmzHandle, size);
                if (data != null && data.length > 0) {
                    return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                }
            }
        } catch (Exception e) {
            callback.onLog("[ERROR] Error leyendo KMZ desde RC 2: " + e.getMessage());
        }
        return "";
    }

    public List<String> scanWaypointSlots() {
        List<String> slots = new ArrayList<>();
        List<DeviceSlotInfo> detailed = getDeviceSlotsDetailed();
        for (DeviceSlotInfo d : detailed) {
            slots.add(d.guid);
        }
        return slots;
    }

    public boolean overwriteMissionKmz(String slotGuid, File localKmzFile) {
        if (mtpDevice == null) {
            callback.onLog("[ERROR] MTP no está conectado al control remoto.");
            return false;
        }
        if (!localKmzFile.exists() || localKmzFile.length() == 0) {
            callback.onLog("[ERROR] El archivo KMZ no existe o está vacío: " + localKmzFile.getAbsolutePath());
            return false;
        }

        try {
            int[] storageIds = mtpDevice.getStorageIds();
            if (storageIds == null || storageIds.length == 0) {
                callback.onLog("[ERROR] Almacenamiento no accesible en el control.");
                return false;
            }

            int targetStorage = storageIds[0];
            int waypointHandle = -1;

            // Find existing waypoint folder across storage IDs
            for (int sid : storageIds) {
                for (String[] pathParts : WAYPOINT_PATH_CANDIDATES) {
                    int h = findHandleForPath(sid, pathParts);
                    if (h != -1) {
                        targetStorage = sid;
                        waypointHandle = h;
                        break;
                    }
                }
                if (waypointHandle != -1) break;
            }

            if (waypointHandle == -1) {
                // Fallback to standard path on primary storage
                waypointHandle = findHandleForPath(targetStorage, new String[]{"Android", "data", "dji.go.v5", "files", "waypoint"});
            }

            if (waypointHandle == -1) {
                callback.onLog("[ERROR] Carpeta de misiones DJI Fly no encontrada en RC 2.");
                return false;
            }

            callback.onLog("[INFO] Buscando ranura a sobrescribir: " + slotGuid);
            int slotFolderHandle = -1;
            int[] slots = mtpDevice.getObjectHandles(targetStorage, 0, waypointHandle);
            if (slots != null) {
                for (int h : slots) {
                    MtpObjectInfo info = mtpDevice.getObjectInfo(h);
                    if (info != null && slotGuid.equalsIgnoreCase(info.getName())) {
                        slotFolderHandle = h;
                        break;
                    }
                }
            }

            if (slotFolderHandle == -1) {
                callback.onLog("[WARN] Ranura " + slotGuid + " no existe. Creando nueva carpeta...");
                MtpObjectInfo.Builder folderBuilder = new MtpObjectInfo.Builder();
                folderBuilder.setName(slotGuid);
                folderBuilder.setParent(waypointHandle);
                folderBuilder.setStorageId(targetStorage);
                folderBuilder.setFormat(MtpConstants.FORMAT_ASSOCIATION);
                MtpObjectInfo createdFolder = mtpDevice.sendObjectInfo(folderBuilder.build());
                if (createdFolder != null) {
                    slotFolderHandle = createdFolder.getObjectHandle();
                    callback.onLog("[OK] Carpeta de ranura creada (Handle: " + slotFolderHandle + ")");
                } else {
                    callback.onLog("[ERROR] No se pudo crear la ranura en DJI Fly.");
                    return false;
                }
            }

            String targetFileName = slotGuid + ".kmz";
            callback.onLog("[INFO] Verificando misión previa: " + targetFileName);

            int[] filesInSlot = mtpDevice.getObjectHandles(targetStorage, 0, slotFolderHandle);
            if (filesInSlot != null) {
                for (int fh : filesInSlot) {
                    MtpObjectInfo finfo = mtpDevice.getObjectInfo(fh);
                    if (finfo != null && (targetFileName.equalsIgnoreCase(finfo.getName()) || finfo.getName().toLowerCase().endsWith(".kmz"))) {
                        callback.onLog("[INFO] Eliminando misión anterior (Handle: " + fh + ")...");
                        mtpDevice.deleteObject(fh);
                    }
                }
            }

            long fileSize = localKmzFile.length();
            callback.onLog(String.format("[INFO] Preparando envío de nuevo KMZ (%d bytes)...", fileSize));

            MtpObjectInfo.Builder fileBuilder = new MtpObjectInfo.Builder();
            fileBuilder.setName(targetFileName);
            fileBuilder.setParent(slotFolderHandle);
            fileBuilder.setStorageId(targetStorage);
            fileBuilder.setCompressedSize(fileSize);
            fileBuilder.setFormat(MtpConstants.FORMAT_UNDEFINED);

            MtpObjectInfo sentInfo = mtpDevice.sendObjectInfo(fileBuilder.build());
            if (sentInfo == null) {
                callback.onLog("[ERROR] sendObjectInfo fue rechazado por el control RC 2.");
                return false;
            }

            int newObjectHandle = sentInfo.getObjectHandle();
            callback.onLog("[OK] Descriptor asignado en RC 2 (Handle: " + newObjectHandle + "). Transfiriendo datos...");

            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(localKmzFile, ParcelFileDescriptor.MODE_READ_ONLY);
            boolean sendOk = mtpDevice.sendObject(newObjectHandle, (int) fileSize, pfd);
            pfd.close();

            if (sendOk) {
                callback.onLog("[ÉXITO] 🎉 ¡Misión sobrescrita con éxito en DJI Fly!");
                return true;
            } else {
                callback.onLog("[ERROR] Falló la transferencia binaria de sendObject.");
                return false;
            }
        } catch (Exception e) {
            callback.onLog("[ERROR] Excepción durante sobreescritura MTP: " + e.getMessage());
            return false;
        }
    }

    /** Deep deletion of a slot folder or file across ALL storage volumes and candidate paths. */
    public boolean deleteDeviceSlot(String slotGuid) {
        if (mtpDevice == null) {
            callback.onLog("[ERROR] MTP no está conectado para eliminar.");
            return false;
        }
        try {
            int[] storageIds = mtpDevice.getStorageIds();
            if (storageIds == null || storageIds.length == 0) return false;

            boolean deletedAny = false;

            for (int storageId : storageIds) {
                for (String[] pathParts : WAYPOINT_PATH_CANDIDATES) {
                    int parentHandle = findHandleForPath(storageId, pathParts);
                    if (parentHandle == -1) continue;

                    int[] children = mtpDevice.getObjectHandles(storageId, 0, parentHandle);
                    if (children == null) continue;

                    for (int handle : children) {
                        MtpObjectInfo info = mtpDevice.getObjectInfo(handle);
                        if (info != null && info.getName() != null) {
                            String name = info.getName();
                            if (slotGuid.equalsIgnoreCase(name) || name.equalsIgnoreCase(slotGuid + ".kmz")) {
                                callback.onLog("[INFO] Eliminando contenido de " + name + " en MTP...");

                                // If folder, recursively delete contents
                                int[] subFiles = mtpDevice.getObjectHandles(storageId, 0, handle);
                                if (subFiles != null) {
                                    for (int fh : subFiles) {
                                        mtpDevice.deleteObject(fh);
                                    }
                                }
                                // Delete folder/file handle
                                boolean ok = mtpDevice.deleteObject(handle);
                                if (!ok) {
                                    try { Thread.sleep(150); } catch (Exception ignored) {}
                                    ok = mtpDevice.deleteObject(handle);
                                }

                                MtpObjectInfo verifyInfo = mtpDevice.getObjectInfo(handle);
                                if (verifyInfo == null || ok) {
                                    deletedAny = true;
                                    callback.onLog("[ÉXITO] 🗑️ Misión/Ranura " + name + " eliminada físicamente del RC 2.");
                                }
                            }
                        }
                    }
                }
            }

            return deletedAny;
        } catch (Exception e) {
            callback.onLog("[ERROR] Error eliminando ranura en RC 2: " + e.getMessage());
        }
        return false;
    }

    public boolean createNewMissionSlot(File localKmzFile) {
        String newGuid = java.util.UUID.randomUUID().toString().toUpperCase();
        callback.onLog("[INFO] Creando NUEVA ranura en DJI Fly: " + newGuid);
        return overwriteMissionKmz(newGuid, localKmzFile);
    }
}
