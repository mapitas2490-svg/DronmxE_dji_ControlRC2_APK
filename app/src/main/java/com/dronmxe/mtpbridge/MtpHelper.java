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
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    private volatile boolean isOpening = false;
    private volatile boolean isScanning = false;
    private volatile String customWaypointPath = "";

    public void setCustomWaypointPath(String path) {
        this.customWaypointPath = (path != null) ? path.trim() : "";
        if (!this.customWaypointPath.isEmpty()) {
            callback.onLog("[MTP] Ruta de búsqueda personalizada configurada: " + this.customWaypointPath);
        }
    }

    public String getCustomWaypointPath() {
        return customWaypointPath != null ? customWaypointPath : "";
    }

    // Rutas candidatas para misiones en DJI RC 2 / RC Pro / DJI Fly
    private static final String[][] WAYPOINT_PATH_CANDIDATES = new String[][]{
            {"Android", "data", "dji.go.v5", "files", "Waypoint"},
            {"Android", "data", "dji.go.v5", "files", "waypoint"},
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
            {"Waypoint"},
            {"waypoint"}
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
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
    }

    public void unregister() {
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

    public void findAndConnectDevice() {
        if (isOpening) return;
        mtpExecutor.execute(() -> {
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            UsbDevice targetDevice = null;
            for (UsbDevice dev : deviceList.values()) {
                if (dev.getVendorId() == DJI_VENDOR_ID) {
                    targetDevice = dev; break;
                }
            }
            if (targetDevice == null) {
                for (UsbDevice dev : deviceList.values()) {
                    if (dev.getDeviceClass() == UsbConstants.USB_CLASS_STILL_IMAGE || dev.getDeviceClass() == 0) {
                        targetDevice = dev; break;
                    }
                }
            }
            if (targetDevice == null) {
                callback.onDeviceStatus(false, "RC 2 no detectado");
                return;
            }
            if (usbManager.hasPermission(targetDevice)) {
                openMtpDevice(targetDevice);
            } else {
                callback.onLog("[USB] Solicitando permiso...");
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
                PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
                usbManager.requestPermission(targetDevice, pi);
            }
        });
    }

    private void openMtpDevice(UsbDevice device) {
        if (isOpening) return;
        isOpening = true;
        
        callback.onLog("[MTP] Iniciando sesión...");
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            callback.onDeviceStatus(false, "Fallo apertura USB");
            isOpening = false;
            return;
        }

        MtpDevice mDevice = new MtpDevice(device);
        // open() puede tardar 10s en RC 2, se ejecuta fuera de lock para no colgar la UI
        if (!mDevice.open(connection)) {
            callback.onDeviceStatus(false, "MTP Rechazada (¿Desbloqueado?)");
            connection.close();
            isOpening = false;
            return;
        }

        // Bucle de detección de discos (Storage)
        int[] storageIds = null;
        for (int i = 0; i < 10; i++) {
            storageIds = mDevice.getStorageIds();
            if (storageIds != null && storageIds.length > 0) break;
            callback.onLog("[MTP] Buscando discos (" + (i+1) + "/10)...");
            try { Thread.sleep(1000); } catch (Exception ignored) {}
        }

        synchronized (mtpLock) {
            closeConnection();
            currentDevice = device;
            currentConnection = connection;
            mtpDevice = mDevice;
        }

        if (storageIds == null || storageIds.length == 0) {
            callback.onDeviceStatus(true, "Conectado (bloqueado)");
            callback.onLog("[!] RC 2 bloqueado. Elige 'Transferencia de Archivos' en el control.");
        } else {
            callback.onLog("[OK] DJI RC 2 Conectado con " + storageIds.length + " unidades.");
            callback.onDeviceStatus(true, "DJI RC 2 Conectado y Listo");
        }
        isOpening = false;
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

    public List<DeviceSlotInfo> getDeviceSlotsDetailed() {
        if (isScanning) return new ArrayList<>();
        isScanning = true;
        List<DeviceSlotInfo> slots = new ArrayList<>();
        MtpDevice device;
        synchronized (mtpLock) { device = mtpDevice; }
        if (device == null) { isScanning = false; return slots; }

        try {
            int[] storageIds = device.getStorageIds();
            if (storageIds == null || storageIds.length == 0) { isScanning = false; return slots; }

            Set<String> seen = new HashSet<>();
            for (int storageId : storageIds) {
                MtpStorageInfo si = device.getStorageInfo(storageId);
                String storageName = (si != null) ? si.getDescription() : "Memoria";
                callback.onLog("[MTP] Escaneando unidad: " + storageName);

                // Diagnóstico: listar lo que hay en la raíz de esta unidad con parentHandle = 0xFFFFFFFF
                int[] rootItems = device.getObjectHandles(storageId, 0, 0xFFFFFFFF);
                if (rootItems != null) {
                    StringBuilder sb = new StringBuilder("[MTP] Carpetas raíz: ");
                    for (int rh : rootItems) {
                        MtpObjectInfo ri = device.getObjectInfo(rh);
                        if (ri != null) sb.append(ri.getName()).append(", ");
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
                                        slot.base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
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
        } catch (Exception e) {
            callback.onLog("[ERR] Escaneo: " + e.getMessage());
        }
        isScanning = false;
        return slots;
    }

    private int findHandleForPath(MtpDevice device, int storageId, String[] pathParts) {
        int currentHandle = 0xFFFFFFFF; // Usar -1 (0xFFFFFFFF) como raíz
        for (String part : pathParts) {
            int foundHandle = -1;
            int[] children = device.getObjectHandles(storageId, 0, currentHandle);
            if (children == null) return -1;
            for (int h : children) {
                MtpObjectInfo info = device.getObjectInfo(h);
                if (info != null && part.equalsIgnoreCase(info.getName())) {
                    foundHandle = h; break;
                }
            }
            if (foundHandle == -1) return -1;
            currentHandle = foundHandle;
        }
        return currentHandle;
    }

    private KmzParsedInfo parseKmzFast(byte[] data) {
        KmzParsedInfo info = new KmzParsedInfo();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".wpml") || name.endsWith(".kml")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int r;
                    while ((r = zis.read(buf)) != -1) baos.write(buf, 0, r);
                    String xml = baos.toString("UTF-8");
                    int c = 0, i = 0;
                    while ((i = xml.indexOf("<wpml:waypoint", i)) != -1) { c++; i += 14; }
                    if (c == 0) { i = 0; while ((i = xml.indexOf("<Placemark", i)) != -1) { c++; i += 10; } }
                    info.wpCount = Math.max(info.wpCount, c);
                    if (info.missionName.isEmpty()) {
                        int s = xml.indexOf("<wpml:missionName>");
                        if (s != -1) {
                            int e = xml.indexOf("</wpml:missionName>", s);
                            if (e != -1) info.missionName = xml.substring(s + 18, e).replace("<![CDATA[", "").replace("]]>", "").trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return info;
    }

    private static class KmzParsedInfo { int wpCount = 0; String missionName = ""; }

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
                            RemoteKmzFile r = new RemoteKmzFile();
                            r.name = fi.getName(); r.size = fi.getCompressedSize();
                            r.dateModified = fi.getDateModified() * 1000L; r.handle = fh;
                            result.add(r);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    public static class RemoteKmzFile { public String name = ""; long size = 0; long dateModified = 0; int handle = -1; }

    public String readSlotKmzBase64(int handle, int size) {
        MtpDevice device; synchronized (mtpLock) { device = mtpDevice; }
        if (device == null || handle <= 0) return "";
        try {
            if (size > 0 && size < 12000000) {
                byte[] data = device.getObject(handle, size);
                if (data != null) return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            }
        } catch (Exception ignored) {}
        return "";
    }

    public boolean overwriteMissionKmz(String slotGuid, File localFile) {
        MtpDevice device; synchronized (mtpLock) { device = mtpDevice; }
        if (device == null || !localFile.exists()) return false;
        try {
            // Asegurar formato WPML estándar de DJI Fly (wpmz/template.kml + wpmz/waylines.wpml)
            File fileToSend = WpmlKmzBuilder.ensureDjiWpmlKmz(localFile, context.getCacheDir());

            int[] storageIds = device.getStorageIds();
            if (storageIds == null || storageIds.length == 0) return false;
            int storageId = storageIds[0]; int waypointHandle = -1;
            for (int sid : storageIds) {
                if (customWaypointPath != null && !customWaypointPath.trim().isEmpty()) {
                    String cleanPath = customWaypointPath.trim().replaceAll("^/+|/+$", "");
                    String[] parts = cleanPath.split("[/\\\\]+");
                    waypointHandle = findHandleForPath(device, sid, parts);
                }
                if (waypointHandle == -1) {
                    for (String[] path : WAYPOINT_PATH_CANDIDATES) {
                        int h = findHandleForPath(device, sid, path);
                        if (h != -1) { waypointHandle = h; storageId = sid; break; }
                    }
                }
                if (waypointHandle != -1) break;
            }
            if (waypointHandle == -1) return false;
            int folderHandle = -1;
            int[] items = device.getObjectHandles(storageId, 0, waypointHandle);
            if (items != null) {
                for (int h : items) {
                    MtpObjectInfo info = device.getObjectInfo(h);
                    if (info != null && slotGuid.equalsIgnoreCase(info.getName())) { folderHandle = h; break; }
                }
            }
            if (folderHandle == -1) {
                MtpObjectInfo.Builder b = new MtpObjectInfo.Builder();
                b.setName(slotGuid); b.setParent(waypointHandle); b.setStorageId(storageId); b.setFormat(MtpConstants.FORMAT_ASSOCIATION);
                MtpObjectInfo created = device.sendObjectInfo(b.build());
                if (created == null) return false;
                folderHandle = created.getObjectHandle();
            }
            int[] files = device.getObjectHandles(storageId, 0, folderHandle);
            if (files != null) { for (int fh : files) device.deleteObject(fh); }
            MtpObjectInfo.Builder fb = new MtpObjectInfo.Builder();
            fb.setName(slotGuid + ".kmz"); fb.setParent(folderHandle); fb.setStorageId(storageId); fb.setCompressedSize(fileToSend.length()); fb.setFormat(MtpConstants.FORMAT_UNDEFINED);
            MtpObjectInfo sent = device.sendObjectInfo(fb.build());
            if (sent == null) return false;
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(fileToSend, ParcelFileDescriptor.MODE_READ_ONLY);
            boolean ok = device.sendObject(sent.getObjectHandle(), (int) fileToSend.length(), pfd);
            pfd.close();
            return ok;
        } catch (Exception e) { return false; }
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
