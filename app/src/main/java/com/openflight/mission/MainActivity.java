package com.openflight.mission;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.mtp.MtpConstants;
import android.mtp.MtpDevice;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 2001;
    private static final int NATIVE_PICKER_REQUEST_CODE = 2002;
    public static final String ACTION_USB_PERMISSION = "com.openflight.mission.USB_PERMISSION";

    public static final StringBuilder DIAGNOSTIC_LOG = new StringBuilder();

    public static void log(String tag, String msg) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String entry = "[" + timestamp + "][" + tag + "] " + msg;
        android.util.Log.i("dronmxE", entry);
        synchronized (DIAGNOSTIC_LOG) {
            DIAGNOSTIC_LOG.append(entry).append("\n");
            if (DIAGNOSTIC_LOG.length() > 30000) {
                DIAGNOSTIC_LOG.delete(0, 10000);
            }
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("USB_EVENT", "Evento recibido: " + action);
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    log("USB_PERM", "Permiso para " + (device != null ? device.getDeviceName() : "null") + " concedido=" + granted);
                    if (granted && device != null && webView != null) {
                        webView.post(() -> webView.evaluateJavascript("if (typeof refreshDjiSlots === 'function') refreshDjiSlots(true);", null));
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                log("USB_ATTACH", "Dispositivo conectado al puerto: " + (device != null ? (device.getDeviceName() + " (" + device.getVendorId() + ":" + device.getProductId() + ")") : "desconocido"));
                if (webView != null) {
                    webView.post(() -> webView.evaluateJavascript("if (typeof onUsbHardwareChanged === 'function') onUsbHardwareChanged(true);", null));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                log("USB_DETACH", "Dispositivo desconectado del puerto: " + (device != null ? device.getDeviceName() : "desconocido"));
                if (webView != null) {
                    webView.post(() -> webView.evaluateJavascript("if (typeof onUsbHardwareChanged === 'function') onUsbHardwareChanged(false);", null));
                }
            } else if ("android.hardware.usb.action.USB_STATE".equals(action)) {
                boolean connected = intent.getBooleanExtra("connected", false);
                boolean configured = intent.getBooleanExtra("configured", false);
                boolean mtp = intent.getBooleanExtra("mtp", false);
                boolean adb = intent.getBooleanExtra("adb", false);
                log("USB_STATE", "Estado USB: connected=" + connected + ", configured=" + configured + ", mtp=" + mtp + ", adb=" + adb);
                if (webView != null) {
                    webView.post(() -> webView.evaluateJavascript("if (typeof onUsbHardwareChanged === 'function') onUsbHardwareChanged(" + connected + ");", null));
                }
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action) || Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                boolean plugged = Intent.ACTION_POWER_CONNECTED.equals(action);
                log("USB_POWER", "Cable USB alimentación/datos: " + (plugged ? "ENCHUFADO" : "DESENCHUFADO"));
                if (webView != null) {
                    webView.post(() -> webView.evaluateJavascript("if (typeof onUsbHardwareChanged === 'function') onUsbHardwareChanged(" + plugged + ");", null));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        webView = new WebView(this);
        setContentView(webView);

        requestStoragePermissions();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setGeolocationEnabled(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    String[] mimeTypes = {
                        "application/vnd.google-earth.kml+xml",
                        "application/vnd.google-earth.kmz",
                        "application/xml",
                        "text/xml",
                        "application/zip",
                        "application/octet-stream",
                        "*/*"
                    };
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    startActivityForResult(Intent.createChooser(intent, "Seleccionar archivo KML / KMZ"), FILE_CHOOSER_REQUEST_CODE);
                    return true;
                } catch (Exception e) {
                    mFilePathCallback = null;
                    return false;
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("mailto:")) {
                    try {
                        android.content.Intent emailIntent = new android.content.Intent(android.content.Intent.ACTION_SENDTO);
                        emailIntent.setData(android.net.Uri.parse(url));
                        startActivity(emailIntent);
                    } catch (Exception e) {
                        try {
                            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse(url));
                            startActivity(intent);
                        } catch (Exception e2) {
                            Toast.makeText(MainActivity.this, "jhonson2490@gmail.com", Toast.LENGTH_LONG).show();
                        }
                    }
                    return true;
                }
                return false;
            }
        });

        IntentFilter usbFilter = new IntentFilter(ACTION_USB_PERMISSION);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbFilter.addAction("android.hardware.usb.action.USB_STATE");
        usbFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        usbFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(usbReceiver, usbFilter);

        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }

        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 100);
        }
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }


        @JavascriptInterface
        public boolean ensureHistoryFileExists() {
            try {
                File waypointDir = new File(Environment.getExternalStorageDirectory(), "Android/data/dji.go.v5/files/waypoint");
                if (!waypointDir.exists()) {
                    waypointDir.mkdirs();
                }
                File historyFile = new File(waypointDir, ".offlineflightmission_history.txt");
                if (!historyFile.exists()) {
                    historyFile.createNewFile();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private List<String> listDirectoryViaShell(String dirPath) {
            List<String> list = new ArrayList<>();
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "ls -1a \"" + dirPath + "\""});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.equals(".") && !line.equals("..")) {
                        list.add(line);
                    }
                }
                p.waitFor();
            } catch (Exception ignored) {}
            return list;
        }

        private List<File> getPossibleWaypointRoots() {
            List<File> roots = new ArrayList<>();
            File ext = Environment.getExternalStorageDirectory();

            // 1. Memoria interna estándar y carpetas ocultas de DJI
            File[] internalCandidates = new File[]{
                new File(ext, "Android/data/dji.go.v5/files/waypoint"),
                new File(ext, "Android/data/dji.go.v5/files/.waypoint"),
                new File(ext, "Android/data/dji.go.v5/files/.waypoint_history"),
                new File(ext, "Android/data/dji.go.v5/files/Waypoint"),
                new File(ext, "DJI/dji.go.v5/files/waypoint"),
                new File(ext, "DJI/dji.go.v5/files/.waypoint"),
                new File(ext, ".dji/waypoint"),
                new File(ext, ".dji.go.v5/waypoint"),
                new File(ext, ".waypoint"),
                new File(ext, "Android/media/dji.go.v5/files/waypoint"),
                new File(ext, "Android/data/dji.go.v5/files/FlightRecord")
            };
            for (File c : internalCandidates) {
                if (!roots.contains(c)) roots.add(c);
            }

            // 2. Tarjetas SD montadas en /storage/ (y carpetas ocultas en SD)
            try {
                File storage = new File("/storage");
                if (storage.exists() && storage.isDirectory()) {
                    File[] mounts = storage.listFiles();
                    if (mounts == null || mounts.length == 0) {
                        List<String> shMounts = listDirectoryViaShell("/storage");
                        List<File> temp = new ArrayList<>();
                        for (String sm : shMounts) temp.add(new File(storage, sm));
                        mounts = temp.toArray(new File[0]);
                    }
                    if (mounts != null) {
                        for (File m : mounts) {
                            if (m.isDirectory() && !m.getName().equals("emulated") && !m.getName().equals("self")) {
                                File[] sdCandidates = new File[]{
                                    new File(m, "Android/data/dji.go.v5/files/waypoint"),
                                    new File(m, "Android/data/dji.go.v5/files/.waypoint"),
                                    new File(m, "Android/media/dji.go.v5/files/waypoint"),
                                    new File(m, "DJI/dji.go.v5/files/waypoint"),
                                    new File(m, "DJI/dji.go.v5/files/.waypoint"),
                                    new File(m, ".dji/waypoint"),
                                    new File(m, "dji.go.v5/files/waypoint"),
                                    new File(m, "waypoint"),
                                    new File(m, ".waypoint")
                                };
                                for (File cand : sdCandidates) {
                                    if (!roots.contains(cand)) roots.add(cand);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 3. Volúmenes adicionales vía ContextCompat
            try {
                File[] extDirs = ContextCompat.getExternalFilesDirs(mContext, null);
                if (extDirs != null) {
                    for (File f : extDirs) {
                        if (f != null) {
                            String p = f.getAbsolutePath();
                            int idx = p.indexOf("/Android/");
                            if (idx > 0) {
                                String base = p.substring(0, idx);
                                File[] candList = new File[]{
                                    new File(base, "Android/data/dji.go.v5/files/waypoint"),
                                    new File(base, "Android/data/dji.go.v5/files/.waypoint")
                                };
                                for (File c : candList) {
                                    if (!roots.contains(c)) roots.add(c);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 4. Rutas directas habituales de tarjetas MicroSD en DJI RC / Android
            String[] commonSdRoots = new String[]{
                "/storage/sdcard1/Android/data/dji.go.v5/files/waypoint",
                "/mnt/media_rw/Android/data/dji.go.v5/files/waypoint",
                "/mnt/sdcard/Android/data/dji.go.v5/files/waypoint",
                "/storage/sdcard0/Android/data/dji.go.v5/files/waypoint"
            };
            for (String r : commonSdRoots) {
                File f = new File(r);
                if (!roots.contains(f)) roots.add(f);
            }

            return roots;
        }

        private void scanMtpDevices(JSONArray array, Set<String> seenGuids) {
            try {
                log("MTP_SCAN", "--- Iniciando escaneo de dispositivos USB MTP ---");
                UsbManager usbMgr = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
                if (usbMgr == null) {
                    log("MTP_SCAN", "UsbManager es NULL en este sistema.");
                    return;
                }
                HashMap<String, UsbDevice> devices = usbMgr.getDeviceList();
                int devCount = devices != null ? devices.size() : 0;
                log("MTP_SCAN", "Dispositivos USB Host detectados: " + devCount);

                // Comprobar estado de conexión USB periférica (cuando este dispositivo está enchufado a otro)
                Intent batteryStatus = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int chargePlug = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) : -1;
                boolean isUsbPlugged = (chargePlug == BatteryManager.BATTERY_PLUGGED_USB);
                log("MTP_SCAN", "Puerto USB-C (Periférico / Conectado a Celular/PC): " + (isUsbPlugged ? "CONECTADO" : "DESCONECTADO (Batería)"));

                if (devices != null && !devices.isEmpty()) {
                    for (UsbDevice dev : devices.values()) {
                        // Ignorar módem de radio interno C5 del DJI RC 2 (PID 0x1020)
                        if (dev.getProductId() == 0x1020 || (dev.getProductName() != null && dev.getProductName().equalsIgnoreCase("C5"))) {
                            log("MTP_DEV", "Ignorando módem de radio interno C5 (PID=0x1020, no es almacenamiento).");
                            continue;
                        }

                        String devDesc = dev.getDeviceName() + " (VID=0x" + Integer.toHexString(dev.getVendorId()) + 
                                         ", PID=0x" + Integer.toHexString(dev.getProductId()) + 
                                         ", Prod=" + dev.getProductName() + 
                                         ", Manuf=" + dev.getManufacturerName() + ")";
                        log("MTP_DEV", "Analizando: " + devDesc);

                        boolean isMtp = false;
                        for (int i = 0; i < dev.getInterfaceCount(); i++) {
                            UsbInterface ui = dev.getInterface(i);
                            int cls = ui.getInterfaceClass();
                            int sub = ui.getInterfaceSubclass();
                            log("MTP_IFACE", "   Iface[" + i + "]: cls=" + cls + " sub=" + sub + " proto=" + ui.getInterfaceProtocol());
                            if ((cls == UsbConstants.USB_CLASS_STILL_IMAGE && sub == 1)
                                    || cls == 6 || cls == 255 || cls == 0) {
                                isMtp = true;
                            }
                        }

                        if (dev.getVendorId() == 4129 || dev.getVendorId() == 0x2CA3 || 
                            (dev.getProductName() != null && dev.getProductName().toUpperCase().contains("DJI")) ||
                            (dev.getManufacturerName() != null && dev.getManufacturerName().toUpperCase().contains("DJI")) ||
                            (dev.getDeviceName() != null && dev.getDeviceName().toUpperCase().contains("KATMAI"))) {
                            isMtp = true;
                            log("MTP_MATCH", "¡Dispositivo DJI / KATMAI reconocido!");
                        }

                        if (isMtp) {
                            boolean hasPerm = usbMgr.hasPermission(dev);
                            log("MTP_PERM", "Permiso USB para " + dev.getDeviceName() + ": " + (hasPerm ? "CONCEDIDO" : "PENDIENTE"));
                            if (!hasPerm) {
                                try {
                                    PendingIntent pi = PendingIntent.getBroadcast(
                                        mContext, 0, new Intent(ACTION_USB_PERMISSION),
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
                                    );
                                    usbMgr.requestPermission(dev, pi);
                                    log("MTP_PERM", "Solicitud de permiso enviada a la pantalla.");
                                } catch (Exception permEx) {
                                    log("MTP_PERM_ERR", "Error pidiendo permiso: " + permEx.getMessage());
                                }
                                JSONObject prompt = new JSONObject();
                                prompt.put("guid", "USB_PROMPT_" + dev.getDeviceId());
                                prompt.put("name", "🔌 [DJI RC 2 MTP Detectado] - Toca para autorizar lectura");
                                prompt.put("lastModified", "Esperando autorización USB");
                                prompt.put("source", "usb_mtp");
                                prompt.put("deviceId", dev.getDeviceId());
                                array.put(prompt);
                            } else {
                                UsbDeviceConnection conn = usbMgr.openDevice(dev);
                                if (conn != null) {
                                    log("MTP_CONN", "UsbDeviceConnection establecida correctamente.");
                                    MtpDevice mtp = new MtpDevice(dev);
                                    if (mtp.open(conn)) {
                                        log("MTP_OPEN", "MtpDevice.open() EXITOSO.");
                                        int[] storageIds = mtp.getStorageIds();
                                        int stCount = storageIds != null ? storageIds.length : 0;
                                        log("MTP_STORAGE", "Almacenamientos MTP encontrados: " + stCount);
                                        if (storageIds != null) {
                                            for (int sId : storageIds) {
                                                log("MTP_STORAGE", "Explorando volumen MTP 0x" + Integer.toHexString(sId));
                                                scanMtpStorageDir(mtp, sId, 0, array, seenGuids, 0);
                                            }
                                        }
                                        mtp.close();
                                    } else {
                                        log("MTP_OPEN_ERR", "mtp.open(conn) falló. Asegúrate de seleccionar 'Transferencia de archivos' en el control.");
                                    }
                                    conn.close();
                                } else {
                                    log("MTP_CONN_ERR", "No se pudo abrir UsbDeviceConnection (conn == null).");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log("MTP_ERR", "Excepción en scanMtpDevices: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void scanMtpStorageDir(MtpDevice mtp, int storageId, int parentHandle, JSONArray array, Set<String> seenGuids, int depth) {
            if (depth > 6) return;
            try {
                int[] handles = mtp.getObjectHandles(storageId, 0, parentHandle);
                if (handles == null) return;
                for (int h : handles) {
                    MtpObjectInfo info = mtp.getObjectInfo(h);
                    if (info == null) continue;
                    String name = info.getName();
                    if (info.getFormat() == MtpConstants.FORMAT_ASSOCIATION) {
                        if ("waypoint".equalsIgnoreCase(name) || ".waypoint".equalsIgnoreCase(name)) {
                            log("MTP_WP_FOUND", "Carpeta waypoint encontrada en MTP! Handle=" + h);
                            int[] slotHandles = mtp.getObjectHandles(storageId, 0, h);
                            if (slotHandles != null) {
                                for (int sh : slotHandles) {
                                    MtpObjectInfo sInfo = mtp.getObjectInfo(sh);
                                    if (sInfo != null && sInfo.getFormat() == MtpConstants.FORMAT_ASSOCIATION) {
                                        String guid = sInfo.getName();
                                        if (seenGuids.contains(guid)) continue;
                                        seenGuids.add(guid);

                                        JSONObject slot = new JSONObject();
                                        slot.put("guid", guid);
                                        slot.put("mtpObjectHandle", sh);
                                        slot.put("mtpStorageId", storageId);
                                        slot.put("source", "usb_mtp");

                                        int[] kmzHandles = mtp.getObjectHandles(storageId, 0, sh);
                                        long sizeKb = 0;
                                        if (kmzHandles != null && kmzHandles.length > 0) {
                                            for (int kh : kmzHandles) {
                                                MtpObjectInfo kInfo = mtp.getObjectInfo(kh);
                                                if (kInfo != null && kInfo.getName().endsWith(".kmz")) {
                                                    sizeKb = kInfo.getCompressedSize() / 1024;
                                                    break;
                                                }
                                            }
                                        }
                                        String sizeStr = sizeKb > 0 ? (" · " + sizeKb + " KB") : "";
                                        slot.put("name", "Misión " + guid.substring(0, Math.min(8, guid.length())) + " [MTP · Control RC 2]" + sizeStr);
                                        slot.put("lastModified", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(sInfo.getDateModified() * 1000L)));
                                        array.put(slot);
                                        log("MTP_SLOT", "Slot MTP agregado: " + guid);
                                    }
                                }
                            }
                        } else if ("Android".equalsIgnoreCase(name) || "data".equalsIgnoreCase(name) || "dji.go.v5".equalsIgnoreCase(name) || "files".equalsIgnoreCase(name) || "DJI".equalsIgnoreCase(name) || ".dji".equalsIgnoreCase(name)) {
                            scanMtpStorageDir(mtp, storageId, h, array, seenGuids, depth + 1);
                        }
                    }
                }
            } catch (Exception e) {
                log("MTP_DIR_ERR", "Error explorando carpeta MTP: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void requestUsbMtpPermission(int deviceId) {
            log("MTP_PERM_REQ", "Usuario tocó botón autorizar USB para id=" + deviceId);
            try {
                UsbManager usbMgr = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
                if (usbMgr != null) {
                    for (UsbDevice dev : usbMgr.getDeviceList().values()) {
                        if (dev.getDeviceId() == deviceId) {
                            PendingIntent pi = PendingIntent.getBroadcast(
                                mContext, 0, new Intent(ACTION_USB_PERMISSION),
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
                            );
                            usbMgr.requestPermission(dev, pi);
                            log("MTP_PERM_REQ", "Permiso solicitado para " + dev.getDeviceName());
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log("MTP_PERM_REQ_ERR", "Error solicitando permiso: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @JavascriptInterface
        public String getDiagnosticLog() {
            saveLogToDisk();
            synchronized (DIAGNOSTIC_LOG) {
                return DIAGNOSTIC_LOG.toString();
            }
        }

        @JavascriptInterface
        public String saveLogToDisk() {
            StringBuilder savedPaths = new StringBuilder();
            try {
                byte[] logBytes;
                synchronized (DIAGNOSTIC_LOG) {
                    logBytes = DIAGNOSTIC_LOG.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }

                // 1. Descargas público (/sdcard/Download/dronmxE_log.txt)
                File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (dl != null) {
                    if (!dl.exists()) dl.mkdirs();
                    File f = new File(dl, "dronmxE_log.txt");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(logBytes);
                    fos.close();
                    savedPaths.append("Download/dronmxE_log.txt ");
                }

                // 2. helpers en memoria interna (/sdcard/helpers/dronmxE_log.txt)
                File ext = Environment.getExternalStorageDirectory();
                File helpers = new File(ext, "helpers");
                if (helpers.exists() || helpers.mkdirs()) {
                    File f = new File(helpers, "dronmxE_log.txt");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(logBytes);
                    fos.close();
                    savedPaths.append("helpers/dronmxE_log.txt ");
                }

                // 3. helpers en MicroSD (volúmenes secundarios ej. /storage/.../helpers/)
                File[] extDirs = ContextCompat.getExternalFilesDirs(mContext, null);
                if (extDirs != null) {
                    for (File d : extDirs) {
                        if (d != null) {
                            String p = d.getAbsolutePath();
                            int idx = p.indexOf("/Android/");
                            if (idx > 0) {
                                File sdRoot = new File(p.substring(0, idx));
                                File sdHelpers = new File(sdRoot, "helpers");
                                if (sdHelpers.exists() || sdHelpers.mkdirs()) {
                                    try {
                                        File f = new File(sdHelpers, "dronmxE_log.txt");
                                        FileOutputStream fos = new FileOutputStream(f);
                                        fos.write(logBytes);
                                        fos.close();
                                        savedPaths.append(f.getAbsolutePath()).append(" ");
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log("SAVE_LOG_ERR", "Error guardando log en disco: " + e.getMessage());
            }
            return savedPaths.toString();
        }

        @JavascriptInterface
        public void clearDiagnosticLog() {
            synchronized (DIAGNOSTIC_LOG) {
                DIAGNOSTIC_LOG.setLength(0);
            }
            log("DIAG", "Log reiniciado por el usuario");
        }

        @JavascriptInterface
        public String getUsbConnectionStatus() {
            JSONObject status = new JSONObject();
            try {
                status.put("deviceModel", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
                status.put("androidVersion", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = mContext.registerReceiver(null, ifilter);
                int chargePlug = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) : -1;
                boolean usbPlugged = (chargePlug == BatteryManager.BATTERY_PLUGGED_USB);
                status.put("usbPlugged", usbPlugged);
                status.put("plugCode", chargePlug);

                UsbManager usbMgr = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
                JSONArray hostDevices = new JSONArray();
                boolean hasDjiDevice = false;
                if (usbMgr != null) {
                    HashMap<String, UsbDevice> map = usbMgr.getDeviceList();
                    if (map != null) {
                        for (UsbDevice d : map.values()) {
                            JSONObject devObj = new JSONObject();
                            devObj.put("id", d.getDeviceId());
                            devObj.put("name", d.getDeviceName());
                            devObj.put("vendorId", d.getVendorId());
                            devObj.put("productId", d.getProductId());
                            devObj.put("hasPermission", usbMgr.hasPermission(d));
                            hostDevices.put(devObj);
                            if (d.getVendorId() == 4129 || d.getVendorId() == 0x2CA3 || 
                                (d.getProductName() != null && d.getProductName().toUpperCase().contains("DJI"))) {
                                hasDjiDevice = true;
                            }
                        }
                    }
                }
                status.put("hostDevices", hostDevices);
                status.put("hasDjiDevice", hasDjiDevice);
                status.put("isHostMode", hostDevices.length() > 0);
                status.put("isConnected", usbPlugged || hostDevices.length() > 0);
            } catch (Exception e) {
                try { status.put("error", e.getMessage()); } catch (Exception ignored) {}
            }
            return status.toString();
        }

        @JavascriptInterface
        public String forceScanDjiWaypointSlots() {
            log("FORCE_SCAN", "=== USUARIO FORZÓ LECTURA DE WAYPOINTS ===");
            try {
                android.content.SharedPreferences prefs = mContext.getSharedPreferences("dronmxe_slots", Context.MODE_PRIVATE);
                prefs.edit().remove("cached_slots").apply();
            } catch (Exception ignored) {}
            String result = getDjiWaypointSlots();
            try {
                JSONArray arr = new JSONArray(result);
                final int count = arr.length();
                runOnUiThread(() -> {
                    String msg = count > 0 ? 
                        ("Lectura completada: " + count + " misiones encontradas.") :
                        ("0 misiones encontradas. Conecta el celular al RC 2 vía USB MTP para leer.");
                    Toast.makeText(mContext, msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception ignored) {}
            return result;
        }

        @JavascriptInterface
        public String getDjiWaypointSlots() {
            JSONArray array = new JSONArray();
            Set<String> seenGuids = new HashSet<>();

            try {
                log("SLOTS_SCAN", "--- Escaneando misiones de DJI Fly ---");
                // 1. Escaneo USB MTP (si está conectado a un teléfono, tablet o PC)
                scanMtpDevices(array, seenGuids);

                // 2. Escaneo exhaustivo del almacenamiento local del control DJI RC 2
                List<File> roots = getPossibleWaypointRoots();
                log("SLOTS_SCAN", "Rutas posibles a inspeccionar en almacenamiento: " + roots.size());
                for (File wpRoot : roots) {
                    boolean exists = wpRoot.exists();
                    boolean isDir = exists && wpRoot.isDirectory();
                    log("STORAGE_ROOT", wpRoot.getAbsolutePath() + " [Existe=" + exists + ", Dir=" + isDir + ", Lectura=" + wpRoot.canRead() + ", Escritura=" + wpRoot.canWrite() + "]");
                    if (isDir) {
                        boolean isSdCard = !wpRoot.getAbsolutePath().startsWith(Environment.getExternalStorageDirectory().getAbsolutePath());
                        boolean isHidden = wpRoot.getName().startsWith(".") || wpRoot.getAbsolutePath().contains("/.");
                        String storageLabel;
                        if (isHidden) {
                            storageLabel = isSdCard ? " [Tarjeta SD (Oculta) · RC 2]" : " [Memoria Oculta · RC 2]";
                        } else {
                            storageLabel = isSdCard ? " [Tarjeta SD · RC 2]" : " [Memoria Interna · RC 2]";
                        }

                        File[] subDirs = wpRoot.listFiles();
                        if (subDirs == null || subDirs.length == 0) {
                            List<String> shDirs = listDirectoryViaShell(wpRoot.getAbsolutePath());
                            log("SHELL_SCAN", "Shell listó en " + wpRoot.getName() + ": " + shDirs.size() + " elementos");
                            if (!shDirs.isEmpty()) {
                                List<File> temp = new ArrayList<>();
                                for (String sd : shDirs) temp.add(new File(wpRoot, sd));
                                subDirs = temp.toArray(new File[0]);
                            }
                        }

                        if (subDirs != null) {
                            log("SLOTS_DIR", "Subdirectorios en " + wpRoot.getAbsolutePath() + ": " + subDirs.length);
                            Arrays.sort(subDirs, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                            for (File slotDir : subDirs) {
                                if (slotDir.isDirectory()) {
                                    String guid = slotDir.getName();
                                    if (seenGuids.contains(guid)) continue;
                                    seenGuids.add(guid);

                                    JSONObject slot = new JSONObject();
                                    slot.put("guid", guid);
                                    slot.put("path", slotDir.getAbsolutePath());
                                    slot.put("isSdCard", isSdCard);

                                    File[] kmzFiles = slotDir.listFiles((d, n) -> n.toLowerCase().endsWith(".kmz") && !n.endsWith(".bak"));
                                    if (kmzFiles == null || kmzFiles.length == 0) {
                                        List<String> shKmz = listDirectoryViaShell(slotDir.getAbsolutePath());
                                        List<File> tempK = new ArrayList<>();
                                        for (String fn : shKmz) {
                                            if (fn.toLowerCase().endsWith(".kmz") && !fn.endsWith(".bak")) {
                                                tempK.add(new File(slotDir, fn));
                                            }
                                        }
                                        kmzFiles = tempK.toArray(new File[0]);
                                    }

                                    String displayName = guid;
                                    long kmzSize = 0;
                                    if (kmzFiles != null && kmzFiles.length > 0) {
                                        displayName = kmzFiles[0].getName().replace(".kmz", "");
                                        kmzSize = kmzFiles[0].length() / 1024;
                                    }
                                    if (displayName.equals(guid)) {
                                        displayName = "Misión " + guid.substring(0, Math.min(8, guid.length()));
                                    }
                                    String sizeInfo = kmzSize > 0 ? (" · " + kmzSize + " KB") : "";
                                    slot.put("name", displayName + storageLabel + sizeInfo);
                                    slot.put("lastModified", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(slotDir.lastModified())));
                                    slot.put("source", isSdCard ? "sdcard" : "internal");
                                    array.put(slot);
                                    log("SLOT_DETECTED", "Misión real encontrada: " + displayName + " (" + guid + ")");
                                }
                            }
                        }
                    }
                }

                log("SLOTS_RESULT", "Total de misiones leídas: " + array.length());
                // Si hay slots reales, guardar en caché. Si no hay ninguno, NO inyectar slots ficticios por defecto.
                android.content.SharedPreferences prefs = mContext.getSharedPreferences("dronmxe_slots", Context.MODE_PRIVATE);
                if (array.length() > 0) {
                    prefs.edit().putString("cached_slots", array.toString()).apply();
                } else {
                    log("SLOTS_RESULT", "0 misiones encontradas. Se retorna lista vacía []. Requiere conectar celular USB MTP.");
                }
            } catch (Exception e) {
                log("SLOTS_ERR", "Error en getDjiWaypointSlots: " + e.getMessage());
                e.printStackTrace();
            }
            return array.toString();
        }

        @JavascriptInterface
        public String saveKmzMission(String base64Data, String missionName, String targetSlotGuid) {
            JSONObject result = new JSONObject();
            try {
                byte[] decodedBytes;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    decodedBytes = Base64.getDecoder().decode(base64Data);
                } else {
                    decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                }

                // 1. Guardar siempre en Descargas público (/sdcard/Download/)
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();
                File kmzFile = new File(downloadDir, missionName + ".kmz");
                FileOutputStream fos = new FileOutputStream(kmzFile);
                fos.write(decodedBytes);
                fos.close();
                result.put("downloadPath", kmzFile.getAbsolutePath());

                boolean injected = false;
                String targetGuid = targetSlotGuid;
                if (targetGuid == null || targetGuid.isEmpty() || "NEW".equals(targetGuid)) {
                    targetGuid = java.util.UUID.randomUUID().toString().toUpperCase();
                }

                // Inyección directa en DJI Fly (busca en todos los roots: Tarjeta SD, Memoria Interna y Carpetas Ocultas del control RC 2)
                List<File> roots = getPossibleWaypointRoots();
                for (File wpRoot : roots) {
                    try {
                        if (!wpRoot.exists()) {
                            wpRoot.mkdirs();
                        }
                        File slotDir = new File(wpRoot, targetGuid);
                        if (!slotDir.exists()) {
                            slotDir.mkdirs();
                        }

                        if (slotDir.exists()) {
                            File targetKmz = new File(slotDir, targetGuid + ".kmz");
                            if (targetKmz.exists()) {
                                File bak = new File(slotDir, targetGuid + ".kmz.bak");
                                targetKmz.renameTo(bak);
                            }

                            boolean written = false;
                            try {
                                FileOutputStream fosSlot = new FileOutputStream(targetKmz);
                                fosSlot.write(decodedBytes);
                                fosSlot.close();

                                File missionKmz = new File(slotDir, missionName + ".kmz");
                                FileOutputStream fosMission = new FileOutputStream(missionKmz);
                                fosMission.write(decodedBytes);
                                fosMission.close();
                                written = true;
                            } catch (Exception ioEx) {
                                // Forzado de escritura mediante Shell de Linux para carpetas ocultas/protegidas
                                try {
                                    Process p = Runtime.getRuntime().exec(new String[]{
                                        "sh", "-c", "mkdir -p \"" + slotDir.getAbsolutePath() + "\" && cp -f \"" + kmzFile.getAbsolutePath() + "\" \"" + targetKmz.getAbsolutePath() + "\" && chmod 666 \"" + targetKmz.getAbsolutePath() + "\""
                                    });
                                    p.waitFor();
                                    if (targetKmz.exists() && targetKmz.length() > 0) {
                                        written = true;
                                    }
                                } catch (Exception shellEx) {
                                    shellEx.printStackTrace();
                                }
                            }

                            if (written) {
                                slotDir.setLastModified(System.currentTimeMillis());
                                targetKmz.setLastModified(System.currentTimeMillis());
                                injected = true;
                                result.put("slotInjected", targetGuid);
                                result.put("targetRoot", wpRoot.getAbsolutePath());

                                File historyFile = new File(wpRoot, ".offlineflightmission_history.txt");
                                if (!historyFile.exists()) {
                                    try { historyFile.createNewFile(); } catch (Exception ignored) {}
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                result.put("success", true);
                final boolean finalInjected = injected;
                runOnUiThread(() -> {
                    String msg = finalInjected ? 
                        ("¡Misión inyectada con éxito en slot de DJI Fly y guardada en Download/" + missionName + ".kmz!") :
                        ("Misión guardada con éxito en Download/" + missionName + ".kmz");
                    Toast.makeText(mContext, msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                try {
                    result.put("success", false);
                    result.put("error", e.getMessage());
                } catch (Exception ignored) {}
                runOnUiThread(() -> Toast.makeText(mContext, "Error al guardar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
            return result.toString();
        }

        @JavascriptInterface
        public void shareKmz(String base64Data, String missionName) {
            runOnUiThread(() -> {
                try {
                    byte[] decodedBytes;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        decodedBytes = Base64.getDecoder().decode(base64Data);
                    } else {
                        decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                    }

                    File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadDir.exists()) downloadDir.mkdirs();
                    File kmzFile = new File(downloadDir, missionName + ".kmz");
                    FileOutputStream fos = new FileOutputStream(kmzFile);
                    fos.write(decodedBytes);
                    fos.close();

                    android.net.Uri fileUri;
                    try {
                        fileUri = androidx.core.content.FileProvider.getUriForFile(mContext, "com.openflight.mission.fileprovider", kmzFile);
                    } catch (Exception e) {
                        fileUri = android.net.Uri.fromFile(kmzFile);
                    }

                    android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    shareIntent.setType("application/vnd.google-earth.kmz");
                    shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
                    shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "Misión DJI: " + missionName);
                    shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, "Misión fotogramétrica generada en dronmxE.");
                    shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    android.content.Intent chooser = android.content.Intent.createChooser(shareIntent, "Compartir Misión KMZ con...");
                    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(chooser);
                } catch (Exception e) {
                    Toast.makeText(mContext, "Error al compartir: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void openEmail(String email) {
            runOnUiThread(() -> {
                try {
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SENDTO);
                    intent.setData(android.net.Uri.parse("mailto:" + email + "?subject=dronmxE%20Misiones"));
                    mContext.startActivity(intent);
                } catch (Exception e) {
                    try {
                        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                        intent.setData(android.net.Uri.parse("mailto:" + email + "?subject=dronmxE%20Misiones"));
                        mContext.startActivity(intent);
                    } catch (Exception e2) {
                        Toast.makeText(mContext, "Correo: " + email, Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public String getGpsLocation() {
            try {
                final android.location.LocationManager lm = (android.location.LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        
                        android.location.Location bestLoc = null;
                        List<String> providers = lm.getProviders(true);
                        if (providers != null) {
                            for (String provider : providers) {
                                android.location.Location l = lm.getLastKnownLocation(provider);
                                if (l != null) {
                                    if (bestLoc == null || (l.hasAccuracy() && bestLoc.hasAccuracy() && l.getAccuracy() < bestLoc.getAccuracy())) {
                                        bestLoc = l;
                                    } else if (bestLoc == null) {
                                        bestLoc = l;
                                    }
                                }
                            }
                        }

                        // Solicitar actualización activa para tener señal fresca
                        runOnUiThread(() -> {
                            try {
                                android.location.LocationListener listener = new android.location.LocationListener() {
                                    @Override
                                    public void onLocationChanged(android.location.Location location) {
                                        try {
                                            lm.removeUpdates(this);
                                        } catch (Exception ignored) {}
                                    }
                                    @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
                                    @Override public void onProviderEnabled(String provider) {}
                                    @Override public void onProviderDisabled(String provider) {}
                                };
                                if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                                    lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 1, listener, android.os.Looper.getMainLooper());
                                }
                                if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                                    lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 1000, 1, listener, android.os.Looper.getMainLooper());
                                }
                            } catch (Exception ignored) {}
                        });

                        if (bestLoc != null) {
                            JSONObject json = new JSONObject();
                            json.put("lat", bestLoc.getLatitude());
                            json.put("lng", bestLoc.getLongitude());
                            json.put("accuracy", bestLoc.hasAccuracy() ? bestLoc.getAccuracy() : 15.0);
                            return json.toString();
                        }
                    }
                }
            } catch (Exception ignored) {}
            return "";
        }

        @JavascriptInterface
        public void openNativeFilePicker() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    String[] mimeTypes = {
                        "application/vnd.google-earth.kml+xml",
                        "application/vnd.google-earth.kmz",
                        "application/xml",
                        "text/xml",
                        "application/zip",
                        "application/octet-stream",
                        "*/*"
                    };
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    startActivityForResult(Intent.createChooser(intent, "Seleccionar archivo KML / KMZ"), NATIVE_PICKER_REQUEST_CODE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Error abriendo selector: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mFilePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        results = new Uri[]{ data.getData() };
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
        } else if (requestCode == NATIVE_PICKER_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                handleNativePickedUri(data.getData());
            }
        }
    }

    private void handleNativePickedUri(Uri uri) {
        try {
            String fileName = "archivo.kml";
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }

            InputStream is = getContentResolver().openInputStream(uri);
            if (is != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(chunk, 0, chunk.length)) != -1) {
                    buffer.write(chunk, 0, bytesRead);
                }
                buffer.flush();
                is.close();
                byte[] fileBytes = buffer.toByteArray();
                String base64Data = Base64.getEncoder().encodeToString(fileBytes);
                final String safeFileName = fileName.replace("'", "\\'");

                runOnUiThread(() -> {
                    webView.evaluateJavascript("if (window.loadKmlFromNative) { window.loadKmlFromNative('" + base64Data + "', '" + safeFileName + "'); }", null);
                });
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error leyendo archivo: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

