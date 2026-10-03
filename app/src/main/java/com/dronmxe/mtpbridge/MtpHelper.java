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
import android.mtp.MtpStorageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class MtpHelper {

    private static final String TAG = "MtpHelper";
    private static final String ACTION_USB_PERMISSION = "com.dronmxe.mtpbridge.USB_PERMISSION";
    public static final int DJI_VENDOR_ID = 0x2CA3;

    public interface LogCallback {
        void onLog(String message);
        void onDeviceStatus(boolean connected, String description);
    }

    private final Context context;
    private final UsbManager usbManager;
    private final LogCallback callback;
    
    private final Object mtpLock = new Object();
    private UsbDevice currentDevice;
    private UsbDeviceConnection currentConnection;
    private MtpDevice mtpDevice;

    private final ExecutorService mtpExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService usbPoller = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private volatile boolean isScanning = false;
    private volatile String customWaypointPath = "Android/data/dji.go.v5/files/Waypoint";
    private final List<DeviceSlotInfo> cachedSlots = new ArrayList<>();

    public void setCustomWaypointPath(String path) {
        this.customWaypointPath = (path != null && !path.trim().isEmpty()) ? path.trim() : "Android/data/dji.go.v5/files/Waypoint";
        try {
            context.getSharedPreferences("mtp_prefs", Context.MODE_PRIVATE)
                   .edit().putString("custom_waypoint_path", this.customWaypointPath).apply();
        } catch (Exception ignored) {}
        callback.onLog("[MTP] Ruta de búsqueda configurada: " + this.customWaypointPath);
    }

    public String getCustomWaypointPath() {
        return customWaypointPath != null ? customWaypointPath : "Android/data/dji.go.v5/files/Waypoint";
    }

    // Rutas candidatas para misiones en DJI RC 2 / RC Pro / DJI Fly
    private static final String[][] WAYPOINT_PATH_CANDIDATES = new String[][]{
            {"Android", "data", "dji.go.v5", "files", "Waypoint"},
            {"Android", "data", "dji.go.v5", "files", "waypoint"},
            {".dji.go.v5", "waypoint"},
            {".dji.go.v5", "Waypoint"},
            {"dji.go.v5", "waypoint"},
            {"dji.go.v5", "Waypoint"},
            {"DJI", "dji.go.v5", "DJI_WAYPOINT"},
            {"DJI", "DJI_WAYPOINT"},
            {"DJI_WAYPOINT"},
            {".waypoint"},
            {"waypoint"},
            {"Waypoint"},
            {"android", "data", "dji.go.v5", "files", "Waypoint"},
            {"android", "data", "dji.go.v5", "files", "waypoint"},
            {"Android", "data", "com.dji.industry.pilot", "files", "Waypoint"},
            {"Android", "data", "com.dji.industry.pilot", "files", "waypoint"},
            {"Android", "data", "dji.pilottwo", "files", "Waypoint"},
            {"Android", "data", "dji.pilottwo", "files", "waypoint"},
            {"DJI", "dji.go.v5", "files", "Waypoint"},
            {"DJI", "dji.go.v5", "files", "waypoint"},
            {"DJI", "Waypoint"},
            {"DJI", "waypoint"},
            {"Download"},
            {"download"}
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) {
                        callback.onLog("[USB] Permiso concedido.");
                        mtpExecutor.execute(() -> openMtpDevice(device));
                    }
                } else {
                    callback.onDeviceStatus(false, "Permiso USB denegado");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                findAndConnectDevice();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                synchronized (mtpLock) {
                    if (currentDevice != null && currentDevice.equals(device)) {
                        mtpExecutor.execute(() -> {
                            closeConnection();
                            callback.onDeviceStatus(false, "Desconectado");
                        });
                    }
                }
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
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }

        try {
            this.customWaypointPath = context.getSharedPreferences("mtp_prefs", Context.MODE_PRIVATE)
                    .getString("custom_waypoint_path", "Android/data/dji.go.v5/files/Waypoint");
        } catch (Exception ignored) {
            this.customWaypointPath = "Android/data/dji.go.v5/files/Waypoint";
        }

        // Monitoreo periódico en segundo plano para hot-plug OTG y cambio de modo USB en RC 2
        usbPoller.scheduleWithFixedDelay(() -> {
            try {
                if (!isDeviceConnected() && !isConnecting.get()) {
                    HashMap<String, UsbDevice> devMap = usbManager.getDeviceList();
                    if (devMap != null && !devMap.isEmpty()) {
                        for (UsbDevice d : devMap.values()) {
                            if (d.getDeviceClass() != UsbConstants.USB_CLASS_HUB) {
                                findAndConnectDevice();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, 1500, 2500, TimeUnit.MILLISECONDS);
    }

    public void unregister() {
        try { usbPoller.shutdownNow(); } catch (Exception ignored) {}
        try { context.unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        mtpExecutor.execute(this::closeConnection);
    }

    private void closeConnection() {
        synchronized (mtpLock) {
            if (mtpDevice != null) {
                try { mtpDevice.close(); } catch (Exception ignored) {}
                mtpDevice = null;
            }
            if (currentConnection != null) {
                try { currentConnection.close(); } catch (Exception ignored) {}
                currentConnection = null;
            }
            currentDevice = null;
        }
    }

    public void forceReconnect() {
        closeConnection();
        findAndConnectDevice();
    }

    public static boolean isCandidateUsbDevice(UsbDevice dev) {
        if (dev == null) return false;
        if (dev.getDeviceClass() == UsbConstants.USB_CLASS_HUB) return false;

        int vid = dev.getVendorId();
        // VIDs conocidos: DJI (11427/0x2CA3), Google MTP (6353/0x18D1), Rockchip (8711/0x2207), MediaTek (3725), Qualcomm (1478)
        if (vid == DJI_VENDOR_ID || vid == 6353 || vid == 8711 || vid == 3725 || vid == 1478) {
            return true;
        }

        String prod = (dev.getProductName() != null) ? dev.getProductName().toLowerCase() : "";
        String mfg = (dev.getManufacturerName() != null) ? dev.getManufacturerName().toLowerCase() : "";
        if (prod.contains("dji") || prod.contains("rc 2") || prod.contains("rc2") || prod.contains("remote") || prod.contains("fly") || prod.contains("android")
                || mfg.contains("dji") || mfg.contains("rockchip") || mfg.contains("google")) {
            return true;
        }

        if (dev.getDeviceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) return true;

        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface intf = dev.getInterface(i);
            int ic = intf.getInterfaceClass();
            if (ic == UsbConstants.USB_CLASS_STILL_IMAGE || ic == 255) {
                return true;
            }
        }
        return false;
    }

    public void findAndConnectDevice() {
        synchronized (mtpLock) {
            if (mtpDevice != null && currentConnection != null) {
                try {
                    int[] sids = mtpDevice.getStorageIds();
                    if (sids != null && sids.length > 0) {
                        callback.onDeviceStatus(true, "DJI RC 2 Conectado y Listo");
                        return;
                    } else {
                        closeConnection();
                    }
                } catch (Exception ignored) {
                    closeConnection();
                }
            }
        }

        if (!isConnecting.compareAndSet(false, true)) {
            return;
        }

        mtpExecutor.execute(() -> {
            try {
                HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
                UsbDevice targetDevice = null;

                // 1. Buscar coincidencia con criterios específicos de RC 2 / MTP
                for (UsbDevice dev : deviceList.values()) {
                    if (isCandidateUsbDevice(dev)) {
                        targetDevice = dev;
                        break;
                    }
                }

                // 2. Si no hubo coincidencia por filtro específico pero hay un dispositivo no-hub conectado por OTG
                if (targetDevice == null) {
                    for (UsbDevice dev : deviceList.values()) {
                        if (dev.getDeviceClass() != UsbConstants.USB_CLASS_HUB) {
                            targetDevice = dev;
                            callback.onLog("[USB] Dispositivo OTG conectado detectado: "
                                    + (dev.getProductName() != null ? dev.getProductName() : "Dispositivo USB")
                                    + " (VID:" + dev.getVendorId() + " PID:" + dev.getProductId() + ")");
                            break;
                        }
                    }
                }

                if (targetDevice == null) {
                    callback.onDeviceStatus(false, "RC 2 no detectado");
                    return;
                }

                callback.onLog("[USB] Vinculando RC 2: "
                        + (targetDevice.getProductName() != null ? targetDevice.getProductName() : "Control USB")
                        + " (VID=" + targetDevice.getVendorId() + ", PID=" + targetDevice.getProductId() + ")");

                if (usbManager.hasPermission(targetDevice)) {
                    openMtpDevice(targetDevice);
                } else {
                    callback.onLog("[USB] Solicitando permiso al sistema Android...");
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
                    PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
                    usbManager.requestPermission(targetDevice, pi);
                }
            } finally {
                isConnecting.set(false);
            }
        });
    }

    private void openMtpDevice(UsbDevice device) {
        synchronized (mtpLock) {
            if (mtpDevice != null && currentConnection != null) {
                try {
                    int[] existingIds = mtpDevice.getStorageIds();
                    if (existingIds != null && existingIds.length > 0) {
                        callback.onLog("[OK] DJI RC 2 reutilizando sesión MTP activa (" + existingIds.length + " unidades).");
                        callback.onDeviceStatus(true, "DJI RC 2 Conectado y Listo");
                        return;
                    }
                } catch (Exception ignored) {}
                closeConnection();
            }
        }

        callback.onLog("[MTP] Iniciando sesión con " + (device.getProductName() != null ? device.getProductName() : "RC 2") + "...");
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            callback.onDeviceStatus(false, "Fallo apertura USB");
            return;
        }

        MtpDevice mDevice = new MtpDevice(device);
        if (!mDevice.open(connection)) {
            callback.onDeviceStatus(false, "MTP Rechazada (¿Desbloqueado?)");
            try { connection.close(); } catch (Exception ignored) {}
            return;
        }

        // Bucle de detección de discos (Storage)
        int[] storageIds = null;
        for (int i = 0; i < 8; i++) {
            try {
                storageIds = mDevice.getStorageIds();
                if (storageIds != null && storageIds.length > 0) break;
            } catch (Exception ignored) {}
            callback.onLog("[MTP] Esperando unidades (" + (i + 1) + "/8)...");
            try { Thread.sleep(700); } catch (Exception ignored) {}
        }

        synchronized (mtpLock) {
            currentDevice = device;
            currentConnection = connection;
            mtpDevice = mDevice;
        }

        if (storageIds == null || storageIds.length == 0) {
            callback.onDeviceStatus(true, "Conectado (bloqueado)");
            callback.onLog("[!] RC 2 detectado pero bloqueado. Toca 'Transferencia de Archivos' en la pantalla del control.");
        } else {
            callback.onLog("[OK] DJI RC 2 Conectado con " + storageIds.length + " unidades.");
            callback.onDeviceStatus(true, "DJI RC 2 Conectado y Listo");
        }
    }

    public static class DeviceSlotInfo {
        public String guid = "";
        public String displayName = "";
        public String storageName = "";
        public long dateModified = 0;
        public long size = 0;
        public int kmzHandle = -1;
        public int folderHandle = -1;
        public int storageId = 0;
        public int wpCount = 0;
        public List<double[]> coords = new ArrayList<>();
        public String base64 = "";
    }

    private List<Integer> getPrioritizedStorageIds(MtpDevice device, int[] rawIds) {
        List<Integer> list = new ArrayList<>();
        if (rawIds == null) return list;
        List<Integer> others = new ArrayList<>();
        for (int id : rawIds) {
            try {
                MtpStorageInfo info = device.getStorageInfo(id);
                String desc = (info != null && info.getDescription() != null) ? info.getDescription().toLowerCase() : "";
                if (desc.contains("interno") || desc.contains("internal") || desc.contains("compartido") || desc.contains("shared")) {
                    list.add(0, id); // Memoria interna del RC 2 tiene máxima prioridad
                } else {
                    others.add(id);
                }
            } catch (Exception e) {
                others.add(id);
            }
        }
        list.addAll(others);
        return list;
    }

    public List<DeviceSlotInfo> getDeviceSlotsDetailed() {
        if (isScanning) return new ArrayList<>();
        isScanning = true;
        List<DeviceSlotInfo> slots = new ArrayList<>();
        MtpDevice device;
        synchronized (mtpLock) { device = mtpDevice; }
        if (device == null) {
            isScanning = false;
            mtpExecutor.execute(this::findAndConnectDevice);
            return slots;
        }

        try {
            int[] storageIds = null;
            // Si el control estaba bloqueado o recién conectado, reintentar varias veces sin reiniciar el bus
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    storageIds = device.getStorageIds();
                    if (storageIds != null && storageIds.length > 0) break;
                } catch (Exception ignored) {}
                if (attempt < 2) {
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                }
            }

            if (storageIds == null || storageIds.length == 0) {
                callback.onDeviceStatus(true, "Conectado (bloqueado)");
                callback.onLog("[!] RC 2 en espera de permiso. Toca 'Transferencia de Archivos' en la pantalla del control RC 2 y luego pulsa ↻.");
                isScanning = false;
                return slots;
            }

            // Si encontró unidades, actualizar estado a listo
            callback.onDeviceStatus(true, "DJI RC 2 Conectado y Listo");

            List<Integer> prioritizedIds = getPrioritizedStorageIds(device, storageIds);
            Set<String> seen = new HashSet<>();
            for (int storageId : prioritizedIds) {
                MtpStorageInfo si = device.getStorageInfo(storageId);
                String storageName = (si != null && si.getDescription() != null && !si.getDescription().trim().isEmpty()) ? si.getDescription() : ("Unidad " + storageId);
                boolean isInternal = storageName.toLowerCase().contains("interno") || storageName.toLowerCase().contains("internal") || storageName.toLowerCase().contains("compartido");
                callback.onLog("[MTP] Escaneando unidad: " + storageName + (isInternal ? " [MEMORIA INTERNA RC 2]" : " [TARJETA SD]"));

                // Diagnóstico: listar lo que hay en la raíz de esta unidad
                int[] rootItems = device.getObjectHandles(storageId, 0, 0xFFFFFFFF);
                if (rootItems == null || rootItems.length == 0) {
                    rootItems = device.getObjectHandles(storageId, 0, 0);
                }
                if (rootItems != null && rootItems.length > 0) {
                    StringBuilder sb = new StringBuilder("[MTP] Carpetas raíz: ");
                    for (int rh : rootItems) {
                        MtpObjectInfo ri = device.getObjectInfo(rh);
                        if (ri != null && ri.getName() != null) sb.append(ri.getName()).append(", ");
                    }
                    callback.onLog(sb.toString());
                }

                int waypointHandle = -1;
                // Intento 0: Ruta personalizada configurada por el usuario
                if (customWaypointPath != null && !customWaypointPath.trim().isEmpty()) {
                    String cleanPath = customWaypointPath.trim().replaceAll("^/+|/+$", "");
                    String[] parts = cleanPath.split("[/\\\\]+");
                    waypointHandle = findHandleForPath(device, storageId, parts);
                    if (waypointHandle != -1) {
                        callback.onLog("[SCAN] ¡Ruta personalizada MTP encontrada!: " + customWaypointPath);
                    }
                }
                // Intento 1: Buscar por rutas predefinidas
                if (waypointHandle == -1) {
                    for (String[] path : WAYPOINT_PATH_CANDIDATES) {
                        waypointHandle = findHandleForPath(device, storageId, path);
                        if (waypointHandle != -1) break;
                    }
                }

                if (waypointHandle != -1) {
                    callback.onLog("[SCAN] ¡Carpeta Waypoint localizada!");
                    int[] items = device.getObjectHandles(storageId, 0, waypointHandle);
                    if (items != null) {
                        for (int h : items) {
                            MtpObjectInfo info = device.getObjectInfo(h);
                            if (info == null) continue;
                            String name = info.getName();
                            if (name == null || seen.contains(name.toLowerCase())) continue;

                            DeviceSlotInfo slot = new DeviceSlotInfo();
                            slot.guid = name.endsWith(".kmz") ? name.substring(0, name.length() - 4) : name;
                            slot.storageId = storageId;
                            slot.dateModified = info.getDateModified() * 1000L;
                            slot.size = info.getCompressedSize();

                            if (info.getFormat() == MtpConstants.FORMAT_ASSOCIATION || info.getFormat() == 0) {
                                slot.folderHandle = h;
                                int[] sub = device.getObjectHandles(storageId, 0, h);
                                if (sub != null) {
                                    for (int sh : sub) {
                                        MtpObjectInfo subObj = device.getObjectInfo(sh);
                                        if (subObj != null && subObj.getName() != null && subObj.getName().toLowerCase().endsWith(".kmz")) {
                                            slot.kmzHandle = sh; 
                                            slot.size = subObj.getCompressedSize();
                                            break;
                                        }
                                    }
                                }
                            } else if (name.toLowerCase().endsWith(".kmz")) {
                                slot.kmzHandle = h; slot.folderHandle = waypointHandle;
                            }

                            if (slot.kmzHandle != -1) {
                                // Límite de 3MB para lectura de metadatos/miniaturas
                                if (slot.size > 0 && slot.size < 3000000) {
                                    byte[] data = device.getObject(slot.kmzHandle, (int) slot.size);
                                    if (data != null) {
                                        KmzParsedInfo pi = parseKmzFast(data);
                                        slot.wpCount = pi.wpCount;
                                        slot.displayName = pi.missionName;
                                        slot.coords = pi.coords;
                                        if (slot.coords.isEmpty()) {
                                            slot.base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                                        }
                                    }
                                }
                                if (slot.displayName == null || slot.displayName.isEmpty()) slot.displayName = slot.guid;
                                slots.add(slot);
                                seen.add(name.toLowerCase());
                            }
                        }
                    }
                }
            }
            Collections.sort(slots, (a, b) -> Long.compare(b.dateModified, a.dateModified));
            synchronized (cachedSlots) {
                cachedSlots.clear();
                cachedSlots.addAll(slots);
            }
        } catch (Exception e) {
            callback.onLog("[ERR] Escaneo: " + e.getMessage());
        } finally {
            isScanning = false;
        }
        return slots;
    }

    private int findHandleForPath(MtpDevice device, int storageId, String[] pathParts) {
        int currentHandle = 0xFFFFFFFF; // Usar -1 (0xFFFFFFFF) como raíz
        for (String part : pathParts) {
            int foundHandle = -1;
            int[] children = device.getObjectHandles(storageId, 0, currentHandle);
            if ((children == null || children.length == 0) && currentHandle == 0xFFFFFFFF) {
                children = device.getObjectHandles(storageId, 0, 0);
            }
            if (children == null) return -1;
            for (int h : children) {
                MtpObjectInfo info = device.getObjectInfo(h);
                if (info != null && info.getName() != null && part.equalsIgnoreCase(info.getName().trim())) {
                    foundHandle = h; break;
                }
            }
            if (foundHandle == -1) return -1;
            currentHandle = foundHandle;
        }
        return currentHandle;
    }

    public KmzParsedInfo parseKmzFast(byte[] data) {
        KmzParsedInfo info = new KmzParsedInfo();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.endsWith("waylines.wpml") || (info.wpCount == 0 && (name.endsWith(".wpml") || name.endsWith(".kml")))) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int r;
                    while ((r = zis.read(buf)) != -1) baos.write(buf, 0, r);
                    String xml = baos.toString("UTF-8");

                    if (info.missionName.isEmpty() || info.missionName.equalsIgnoreCase("Document")) {
                        int s = xml.indexOf("<wpml:missionName>");
                        if (s != -1) {
                            int e = xml.indexOf("</wpml:missionName>", s);
                            if (e != -1) {
                                String n = xml.substring(s + 18, e).replace("<![CDATA[", "").replace("]]>", "").trim();
                                if (!n.isEmpty()) info.missionName = n;
                            }
                        }
                    }
                    if (info.missionName.isEmpty() || info.missionName.equalsIgnoreCase("Document")) {
                        int s = xml.indexOf("<name>");
                        if (s != -1) {
                            int e = xml.indexOf("</name>", s);
                            if (e != -1) {
                                String n = xml.substring(s + 6, e).trim();
                                if (!n.equalsIgnoreCase("wpmz") && !n.equalsIgnoreCase("Document") && !n.contains("Trayectoria")) {
                                    info.missionName = n;
                                }
                            }
                        }
                    }

                    List<double[]> pts = new ArrayList<>();
                    int pStart = 0;
                    while ((pStart = xml.indexOf("<Placemark", pStart)) != -1) {
                        int pEnd = xml.indexOf("</Placemark>", pStart);
                        if (pEnd == -1) break;
                        int cStart = xml.indexOf("<coordinates>", pStart);
                        if (cStart != -1 && cStart < pEnd) {
                            int cEnd = xml.indexOf("</coordinates>", cStart);
                            if (cEnd != -1 && cEnd <= pEnd) {
                                String cText = xml.substring(cStart + 13, cEnd).trim();
                                String[] tokens = cText.split("[,\\s]+");
                                if (tokens.length >= 2) {
                                    try {
                                        double lon = Double.parseDouble(tokens[0].trim());
                                        double lat = Double.parseDouble(tokens[1].trim());
                                        if (!Double.isNaN(lon) && !Double.isNaN(lat) && (lon != 0.0 || lat != 0.0)) {
                                            pts.add(new double[]{lon, lat});
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                        pStart = pEnd + 12;
                    }
                    if (!pts.isEmpty()) {
                        info.wpCount = pts.size();
                        info.coords = pts;
                    }
                }
            }
        } catch (Exception ignored) {}
        return info;
    }

    public static class KmzParsedInfo {
        public int wpCount = 0;
        public String missionName = "";
        public List<double[]> coords = new ArrayList<>();
    }

    public List<RemoteKmzFile> scanDownloadKmzFiles() {
        List<RemoteKmzFile> result = new ArrayList<>();
        MtpDevice device; synchronized (mtpLock) { device = mtpDevice; }
        if (device == null) return result;
        try {
            int[] storageIds = device.getStorageIds();
            if (storageIds == null) return result;
            for (int sid : storageIds) {
                int h = findHandleForPath(device, sid, new String[]{"Download"});
                if (h == -1) continue;
                int[] files = device.getObjectHandles(sid, 0, h);
                if (files != null) {
                    for (int fh : files) {
                        MtpObjectInfo fi = device.getObjectInfo(fh);
                        if (fi != null && fi.getName() != null && fi.getName().toLowerCase().endsWith(".kmz")) {
                            boolean exists = false;
                            for (RemoteKmzFile existing : result) {
                                if (existing.name.equalsIgnoreCase(fi.getName())) {
                                    exists = true; break;
                                }
                            }
                            if (!exists) {
                                RemoteKmzFile r = new RemoteKmzFile();
                                r.name = fi.getName(); r.size = fi.getCompressedSize();
                                r.dateModified = fi.getDateModified() * 1000L; r.handle = fh;
                                result.add(r);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    public static class RemoteKmzFile { public String name = ""; long size = 0; long dateModified = 0; int handle = -1; }

    public byte[] readRemoteBytes(int handle, int size) {
        MtpDevice device; synchronized (mtpLock) { device = mtpDevice; }
        if (device == null || handle <= 0) return null;
        try {
            if (size > 0 && size < 15000000) {
                return device.getObject(handle, size);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public String readSlotKmzBase64(int handle, int size) {
        byte[] data = readRemoteBytes(handle, size);
        if (data != null && data.length > 0) {
            return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        }
        return "";
    }

    public boolean isDeviceConnected() {
        synchronized (mtpLock) {
            return mtpDevice != null;
        }
    }

    public int findWaypointHandle(MtpDevice device, int storageId) {
        if (customWaypointPath != null && !customWaypointPath.trim().isEmpty()) {
            String cleanPath = customWaypointPath.trim().replaceAll("^/+|/+$", "");
            String[] parts = cleanPath.split("[/\\\\]+");
            int h = findHandleForPath(device, storageId, parts);
            if (h != -1) return h;
        }
        for (String[] path : WAYPOINT_PATH_CANDIDATES) {
            int h = findHandleForPath(device, storageId, path);
            if (h != -1) return h;
        }
        return -1;
    }

    private boolean injectKmzIntoStorage(MtpDevice device, int storageId, int waypointHandle, String slotGuid, File fileToSend) {
        try {
            int folderHandle = -1;
            int directFileHandle = -1;
            String existingKmzName = null;

            String baseGuid = slotGuid.endsWith(".kmz") ? slotGuid.substring(0, slotGuid.length() - 4) : slotGuid;
            String kmzFileName = baseGuid + ".kmz";

            // 1. Buscar en waypointHandle si la ranura existe como carpeta o como archivo directo
            int[] items = device.getObjectHandles(storageId, 0, waypointHandle);
            if (items != null) {
                for (int h : items) {
                    MtpObjectInfo info = device.getObjectInfo(h);
                    if (info == null || info.getName() == null) continue;
                    String iname = info.getName().trim();

                    // Carpeta del slot (directorio)
                    if ((iname.equalsIgnoreCase(baseGuid) || iname.equalsIgnoreCase(kmzFileName))
                            && (info.getFormat() == MtpConstants.FORMAT_ASSOCIATION || info.getFormat() == 0)) {
                        folderHandle = h;
                    }

                    // Archivo directo con el nombre del slot en la raíz de waypoint
                    if ((iname.equalsIgnoreCase(kmzFileName) || iname.equalsIgnoreCase(baseGuid))
                            && info.getFormat() != MtpConstants.FORMAT_ASSOCIATION && info.getFormat() != 0) {
                        directFileHandle = h;
                    }
                }
            }

            // 2. Si la ranura original era un archivo directo en waypointHandle (sin subcarpeta)
            if (folderHandle == -1 && directFileHandle != -1) {
                callback.onLog("[MTP] Ranura directa localizada en RC 2: " + kmzFileName + ". Reemplazando archivo directo...");
                device.deleteObject(directFileHandle);
                try { Thread.sleep(100); } catch (Exception ignored) {}

                MtpObjectInfo.Builder fb = new MtpObjectInfo.Builder();
                fb.setName(kmzFileName);
                fb.setParent(waypointHandle);
                fb.setStorageId(storageId);
                fb.setCompressedSize(fileToSend.length());
                fb.setFormat(MtpConstants.FORMAT_UNDEFINED);

                MtpObjectInfo sent = device.sendObjectInfo(fb.build());
                if (sent == null) {
                    callback.onLog("[ERR] No se pudo crear el archivo directo " + kmzFileName + " en RC 2");
                    return false;
                }
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(fileToSend, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    boolean sentOk = device.sendObject(sent.getObjectHandle(), (int) fileToSend.length(), pfd);
                    callback.onLog("[MTP] Transmisión directa (" + fileToSend.length() + " B): " + (sentOk ? "✅ ÉXITO" : "❌ FALLÓ"));
                    return sentOk;
                }
            }

            // 3. Si existe un archivo directo huérfano con el mismo nombre en la raíz de waypoints, eliminarlo para evitar misiones duplicadas
            if (directFileHandle != -1) {
                callback.onLog("[MTP] Eliminando archivo huérfano para evitar duplicados...");
                device.deleteObject(directFileHandle);
                try { Thread.sleep(50); } catch (Exception ignored) {}
            }

            // 4. Si no existe la carpeta del slot, crearla
            if (folderHandle == -1) {
                MtpObjectInfo.Builder b = new MtpObjectInfo.Builder();
                b.setName(baseGuid);
                b.setParent(waypointHandle);
                b.setStorageId(storageId);
                b.setFormat(MtpConstants.FORMAT_ASSOCIATION);
                MtpObjectInfo created = device.sendObjectInfo(b.build());
                if (created != null) {
                    folderHandle = created.getObjectHandle();
                    callback.onLog("[MTP] Ranura nueva creada en RC 2: " + baseGuid);
                } else {
                    callback.onLog("[ERR] No se pudo crear la carpeta del slot " + baseGuid);
                    return false;
                }
            } else {
                callback.onLog("[MTP] Ranura existente localizada en RC 2: " + baseGuid);
            }

            // 5. Limpiar archivos anteriores en la carpeta del slot (KMZ anterior, previews, backups)
            int[] sub = device.getObjectHandles(storageId, 0, folderHandle);
            if (sub != null && sub.length > 0) {
                for (int sh : sub) {
                    MtpObjectInfo subObj = device.getObjectInfo(sh);
                    if (subObj != null && subObj.getName() != null) {
                        String sName = subObj.getName().trim().toLowerCase();
                        if (sName.endsWith(".kmz") || sName.endsWith(".jpg") || sName.endsWith(".png") || sName.endsWith(".tmp") || sName.endsWith(".bak")) {
                            if (sName.endsWith(".kmz")) existingKmzName = subObj.getName().trim();
                            boolean del = device.deleteObject(sh);
                            callback.onLog("[MTP] Limpiando previo " + subObj.getName() + ": " + (del ? "OK" : "omitido"));
                        }
                    }
                }
            }

            String targetFileName = (existingKmzName != null && !existingKmzName.isEmpty()) ? existingKmzName : kmzFileName;

            // 6. Inyectar el nuevo KMZ nativo DJI Fly
            MtpObjectInfo.Builder fb = new MtpObjectInfo.Builder();
            fb.setName(targetFileName);
            fb.setParent(folderHandle);
            fb.setStorageId(storageId);
            fb.setCompressedSize(fileToSend.length());
            fb.setFormat(MtpConstants.FORMAT_UNDEFINED);

            MtpObjectInfo sent = device.sendObjectInfo(fb.build());

            // Estrategia de recuperación si sendObjectInfo retorna null (archivo todavía bloqueado o en caché)
            if (sent == null) {
                callback.onLog("[WARN] sendObjectInfo retornó NULL. Recreando ranura limpia en RC 2...");
                try {
                    device.deleteObject(folderHandle);
                    Thread.sleep(150);
                } catch (Exception ignored) {}

                MtpObjectInfo.Builder rb = new MtpObjectInfo.Builder();
                rb.setName(baseGuid);
                rb.setParent(waypointHandle);
                rb.setStorageId(storageId);
                rb.setFormat(MtpConstants.FORMAT_ASSOCIATION);
                MtpObjectInfo recreated = device.sendObjectInfo(rb.build());
                if (recreated != null) {
                    folderHandle = recreated.getObjectHandle();
                    fb.setParent(folderHandle);
                    sent = device.sendObjectInfo(fb.build());
                }
            }

            if (sent == null) {
                callback.onLog("[ERR] No se pudo registrar el nuevo KMZ en RC 2.");
                return false;
            }

            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(fileToSend, ParcelFileDescriptor.MODE_READ_ONLY)) {
                boolean sentOk = device.sendObject(sent.getObjectHandle(), (int) fileToSend.length(), pfd);
                callback.onLog("[MTP] Transmisión de bytes (" + fileToSend.length() + " B): " + (sentOk ? "✅ ÉXITO" : "❌ FALLÓ"));
                return sentOk;
            }
        } catch (Exception e) {
            callback.onLog("[ERR] Fallo al inyectar en storage " + storageId + ": " + e.getMessage());
            return false;
        }
    }

    public boolean overwriteMissionKmz(String slotGuid, File localFile) {
        MtpDevice device; synchronized (mtpLock) { device = mtpDevice; }
        if (device == null) {
            callback.onLog("[ERR] Control RC 2 no conectado por MTP. Ejecutando inyección en almacenamiento local...");
            return overwriteLocalWaypointFallback(slotGuid, localFile);
        }
        if (localFile == null || !localFile.exists()) {
            callback.onLog("[ERR] Archivo origen inexistente: " + (localFile != null ? localFile.getName() : "null"));
            return false;
        }

        File fileToSend = null;
        try {
            String missionTitle = localFile.getName().replace(".kmz", "").replace(".kml", "");
            callback.onLog("[MTP] Empaquetando KMZ DJI Fly nativo (WPML 1.0.6) para: " + localFile.getName());
            fileToSend = WpmlKmzBuilder.buildPureDjiKmz(localFile, context.getCacheDir(), missionTitle);

            int[] storageIds = device.getStorageIds();
            if (storageIds == null || storageIds.length == 0) {
                callback.onLog("[ERR] RC 2 sin unidades de almacenamiento accesibles.");
                return false;
            }

            List<Integer> prioritizedIds = getPrioritizedStorageIds(device, storageIds);

            // PASO 1: Localizar la unidad de almacenamiento donde reside exactamente esta ranura
            int targetStorageId = -1;
            int targetWaypointHandle = -1;
            String baseGuid = slotGuid.endsWith(".kmz") ? slotGuid.substring(0, slotGuid.length() - 4) : slotGuid;

            for (int sId : prioritizedIds) {
                int wHandle = findWaypointHandle(device, sId);
                if (wHandle != -1) {
                    int[] items = device.getObjectHandles(sId, 0, wHandle);
                    if (items != null) {
                        for (int h : items) {
                            MtpObjectInfo info = device.getObjectInfo(h);
                            if (info != null && info.getName() != null) {
                                String iname = info.getName().trim();
                                if (iname.equalsIgnoreCase(baseGuid) || iname.equalsIgnoreCase(baseGuid + ".kmz")) {
                                    targetStorageId = sId;
                                    targetWaypointHandle = wHandle;
                                    break;
                                }
                            }
                        }
                    }
                    if (targetStorageId != -1) break;
                }
            }

            // PASO 2: Si no existía previamente, usar la primera unidad disponible (Memoria Interna preferida)
            if (targetStorageId == -1) {
                for (int sId : prioritizedIds) {
                    int wHandle = findWaypointHandle(device, sId);
                    if (wHandle != -1) {
                        targetStorageId = sId;
                        targetWaypointHandle = wHandle;
                        break;
                    }
                }
            }

            if (targetStorageId != -1 && targetWaypointHandle != -1) {
                MtpStorageInfo si = device.getStorageInfo(targetStorageId);
                String storageName = (si != null && si.getDescription() != null && !si.getDescription().trim().isEmpty()) ? si.getDescription() : ("Unidad " + targetStorageId);
                callback.onLog("[MTP] Sobreescribiendo slot '" + slotGuid + "' en: " + storageName + "...");
                boolean ok = injectKmzIntoStorage(device, targetStorageId, targetWaypointHandle, slotGuid, fileToSend);
                if (ok) {
                    callback.onLog("[OK] 🎉 ¡Misión " + localFile.getName() + " sobreescrita con éxito en RC 2!");
                    callback.onLog("[DJI FLY] ⚠️ En el RC 2: si DJI Fly está abierto, ciérralo en apps recientes (desliza hacia arriba) y ábrelo de nuevo para refrescar las misiones.");
                    return true;
                } else {
                    callback.onLog("[ERR] Falló la inyección en la unidad " + storageName);
                    return false;
                }
            } else {
                callback.onLog("[ERR] No se encontró ninguna carpeta de Waypoint válida en el RC 2.");
                return false;
            }
        } catch (Exception e) {
            callback.onLog("[ERR] Excepción en sobreescritura MTP: " + e.getMessage());
            return false;
        } finally {
            if (fileToSend != null && fileToSend.exists() && fileToSend.getAbsolutePath().contains(context.getCacheDir().getAbsolutePath())) {
                try { fileToSend.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean overwriteLocalWaypointFallback(String slotGuid, File localFile) {
        try {
            File fileToSend = WpmlKmzBuilder.buildPureDjiKmz(localFile, context.getCacheDir(), slotGuid);
            List<File> candidates = new ArrayList<>();
            candidates.add(new File("/storage/emulated/0/Android/data/dji.go.v5/files/waypoint"));
            candidates.add(new File("/storage/emulated/0/Android/data/dji.go.v5/files/Waypoint"));
            candidates.add(new File("/sdcard/Android/data/dji.go.v5/files/waypoint"));
            candidates.add(new File("/sdcard/Android/data/dji.go.v5/files/Waypoint"));
            candidates.add(new File("/storage/emulated/0/.dji.go.v5/waypoint"));
            candidates.add(new File("/sdcard/.dji.go.v5/waypoint"));
            candidates.add(new File("/storage/emulated/0/.waypoint"));
            candidates.add(new File("/sdcard/.waypoint"));

            try {
                File storage = new File("/storage");
                if (storage.exists() && storage.isDirectory()) {
                    File[] mounts = storage.listFiles();
                    if (mounts != null) {
                        for (File m : mounts) {
                            if (m.isDirectory() && !m.getName().equals("emulated") && !m.getName().equals("self")) {
                                candidates.add(new File(m, "Android/data/dji.go.v5/files/waypoint"));
                                candidates.add(new File(m, "Android/data/dji.go.v5/files/Waypoint"));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            boolean written = false;
            for (File cDir : candidates) {
                try {
                    if (cDir.exists() && cDir.isDirectory()) {
                        File sDir = new File(cDir, slotGuid);
                        if (!sDir.exists()) sDir.mkdirs();
                        File dest = new File(sDir, slotGuid + ".kmz");

                        // Limpiar archivos anteriores en la ranura
                        File[] oldFiles = sDir.listFiles();
                        if (oldFiles != null) {
                            for (File of : oldFiles) {
                                if (of.getName().toLowerCase().endsWith(".kmz") || of.getName().toLowerCase().endsWith(".jpg") || of.getName().toLowerCase().endsWith(".png")) {
                                    try { of.delete(); } catch (Exception ignored) {}
                                }
                            }
                        }

                        try (FileInputStream in = new FileInputStream(fileToSend);
                             FileOutputStream out = new FileOutputStream(dest)) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                            written = true;
                        } catch (Exception ioEx) {
                            // Shell fallback para evadir restricciones de Android 11 en Android/data
                            try {
                                Process p = Runtime.getRuntime().exec(new String[]{
                                    "sh", "-c", "mkdir -p \"" + sDir.getAbsolutePath() + "\" && cp -f \"" + fileToSend.getAbsolutePath() + "\" \"" + dest.getAbsolutePath() + "\" && chmod 666 \"" + dest.getAbsolutePath() + "\""
                                });
                                p.waitFor();
                                if (dest.exists() && dest.length() > 0) written = true;
                            } catch (Exception ignored) {}
                        }

                        if (written) {
                            dest.setLastModified(System.currentTimeMillis());
                            sDir.setLastModified(System.currentTimeMillis());
                            callback.onLog("[OK] ✅ Misión sobreescrita en: " + dest.getAbsolutePath());
                            break; // DETENER: Escrito en la primera carpeta válida, NUNCA duplicar
                        }
                    }
                } catch (Exception e) {
                    callback.onLog("[WARN] No se pudo escribir en " + cDir.getAbsolutePath() + ": " + e.getMessage());
                }
            }

            if (written) {
                callback.onLog("[OK] 🎉 Sobreescritura completada en almacenamiento interno del RC 2.");
                callback.onLog("[DJI FLY] ⚠️ En el RC 2: Cierra DJI Fly en apps recientes (desliza hacia arriba) y ábrelo de nuevo para ver los cambios.");
                return true;
            } else {
                callback.onLog("[ERR] No se pudo escribir en las carpetas de DJI Fly en el RC 2.");
                return false;
            }
        } catch (Exception e) {
            callback.onLog("[ERR] Excepción en fallback local: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteDeviceSlot(String slotGuid) {
        MtpDevice device; synchronized (mtpLock) { device = mtpDevice; }
        if (device == null) return false;
        try {
            int[] storageIds = device.getStorageIds();
            for (int sid : storageIds) {
                for (String[] path : WAYPOINT_PATH_CANDIDATES) {
                    int parent = findHandleForPath(device, sid, path);
                    if (parent == -1) continue;
                    int[] items = device.getObjectHandles(sid, 0, parent);
                    if (items == null) continue;
                    for (int h : items) {
                        MtpObjectInfo info = device.getObjectInfo(h);
                        if (info != null && (slotGuid.equalsIgnoreCase(info.getName()) || info.getName().equalsIgnoreCase(slotGuid + ".kmz"))) {
                            int[] sub = device.getObjectHandles(sid, 0, h);
                            if (sub != null) { for (int sh : sub) device.deleteObject(sh); }
                            device.deleteObject(h); return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public boolean createNewMissionSlot(File localFile) {
        return overwriteMissionKmz(java.util.UUID.randomUUID().toString().toUpperCase(), localFile);
    }
}
