package com.dronmxe.mtpbridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements MtpHelper.LogCallback {

    private static final String TAG = "MainActivity";
    private WebView webView;
    private MtpHelper mtpHelper;
    private final List<File> availableKmzFiles = new ArrayList<>();
    private boolean isPageLoaded = false;
    private final List<String> pendingJsCalls = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        ioExecutor.execute(() -> handleSelectedUri(uri));
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        webView.addJavascriptInterface(new BridgeInterface(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("dronmxE")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("dronmxE MTP")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }
        });
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                Log.d(TAG, "Page finished loading: " + url);
                flushPendingJs();
                notifyJs("refreshAll();");
            }
        });

        mtpHelper = new MtpHelper(this, this);
        webView.loadUrl("file:///android_asset/index.html");

        handleIncomingIntent(getIntent());
        checkAndRequestPermissions();
    }

    private synchronized void flushPendingJs() {
        runOnUiThread(() -> {
            for (String script : pendingJsCalls) {
                webView.evaluateJavascript(script, null);
            }
            pendingJsCalls.clear();
        });
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 100);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Connect USB off UI thread
        new Thread(() -> {
            mtpHelper.findAndConnectDevice();
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                ioExecutor.execute(() -> handleSelectedUri(uri));
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            if (uri != null) {
                ioExecutor.execute(() -> handleSelectedUri(uri));
            }
        }
    }

    private void handleSelectedUri(Uri uri) {
        try {
            String displayName = "mision_" + System.currentTimeMillis() + ".kmz";
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        String realName = cursor.getString(nameIndex);
                        if (realName != null && !realName.isEmpty()) displayName = realName;
                    }
                }
            }

            File saveDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dronmxE");
            if (!saveDir.exists()) saveDir.mkdirs();
            File tempFile = new File(saveDir, displayName);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            synchronized (availableKmzFiles) {
                if (!availableKmzFiles.contains(tempFile)) {
                    availableKmzFiles.add(0, tempFile);
                }
            }

            notifyJs("refreshLocalMissions();");
            showToast("Misión recibida: " + displayName);
        } catch (Exception e) {
            showToast("Error leyendo archivo: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mtpHelper != null) {
            mtpHelper.unregister();
        }
        ioExecutor.shutdown();
    }

    @Override
    public void onLog(String message) {
        runOnUiThread(() -> {
            String safe = JSONObject.quote(message);
            notifyJs("onLogMessage(" + safe + ");");
        });
    }

    @Override
    public void onDeviceStatus(boolean connected, String description) {
        runOnUiThread(() -> {
            String safe = JSONObject.quote(description != null ? description : "");
            notifyJs("onDeviceStatusChanged(" + connected + ", " + safe + ");");
        });
    }

    private synchronized void notifyJs(String script) {
        if (webView != null) {
            runOnUiThread(() -> {
                if (isPageLoaded) {
                    webView.evaluateJavascript(script, null);
                } else {
                    pendingJsCalls.add(script);
                }
            });
        }
    }

    private void triggerHaptic() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 150, 100, 250}, -1));
                } else {
                    vibrator.vibrate(new long[]{0, 150, 100, 250}, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    public class BridgeInterface {

        @JavascriptInterface
        public String getDeviceSlotsJson() {
            try {
                List<MtpHelper.DeviceSlotInfo> slots = mtpHelper.getDeviceSlotsDetailed();
                JSONArray arr = new JSONArray();

                for (MtpHelper.DeviceSlotInfo s : slots) {
                    JSONObject obj = new JSONObject();
                    obj.put("guid", s.guid);
                    obj.put("displayName", (s.displayName != null && !s.displayName.isEmpty()) ? s.displayName : s.guid);
                    obj.put("dateModified", s.dateModified);
                    obj.put("size", s.size);
                    obj.put("wpCount", s.wpCount);
                    if (s.coords != null && !s.coords.isEmpty()) {
                        JSONArray coordsArr = new JSONArray();
                        for (double[] pt : s.coords) {
                            JSONArray ptArr = new JSONArray();
                            ptArr.put(pt[0]);
                            ptArr.put(pt[1]);
                            coordsArr.put(ptArr);
                        }
                        obj.put("coords", coordsArr);
                    } else if (s.base64 != null && !s.base64.isEmpty()) {
                        obj.put("base64", s.base64);
                    }
                    arr.put(obj);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void fetchDeviceSlotsAsync() {
            new Thread(() -> {
                String json = getDeviceSlotsJson();
                notifyJs("onDeviceSlotsLoaded(" + json + ");");
            }).start();
        }

        @JavascriptInterface
        public String getLocalMissionsJson() {
            try {
                synchronized (availableKmzFiles) {
                    availableKmzFiles.clear();
                    List<File> searchDirs = new ArrayList<>();
                    File ext = Environment.getExternalStorageDirectory();
                    File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File doc = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);

                    searchDirs.add(dl);
                    searchDirs.add(new File(dl, "dronmxE"));
                    searchDirs.add(doc);
                    searchDirs.add(new File(doc, "dronmxE"));
                    searchDirs.add(new File(ext, "helpers"));
                    searchDirs.add(new File(ext, "DJI"));
                    searchDirs.add(new File(ext, "dronmxE"));
                    searchDirs.add(new File(ext, ".waypoint"));
                    searchDirs.add(getExternalFilesDir(null));
                    searchDirs.add(new File("/sdcard/Download"));
                    searchDirs.add(new File("/sdcard/Download/dronmxE"));
                    searchDirs.add(new File("/sdcard/helpers"));
                    searchDirs.add(new File("/sdcard/DJI"));
                    searchDirs.add(new File("/sdcard/dronmxE"));
                    searchDirs.add(new File("/sdcard/.waypoint"));
                    searchDirs.add(new File("/sdcard/Android/data/dji.go.v5/files/Waypoint"));
                    searchDirs.add(new File("/sdcard/Android/data/dji.go.v5/files/waypoint"));
                    searchDirs.add(new File("/sdcard/Android/data/com.dji.industry.pilot/files/Waypoint"));
                    searchDirs.add(new File("/sdcard/Android/data/com.dji.industry.pilot/files/waypoint"));

                    for (File dir : searchDirs) {
                        if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                        File[] list = dir.listFiles();
                        if (list != null) {
                            for (File f : list) {
                                if (f.isFile() && f.getName().toLowerCase().endsWith(".kmz") && !f.getName().startsWith("pure_dji_") && !f.getName().startsWith("rc2_dl_")) {
                                    boolean duplicate = false;
                                    for (File existing : availableKmzFiles) {
                                        if (existing.getAbsolutePath().equalsIgnoreCase(f.getAbsolutePath()) ||
                                            (existing.getName().equalsIgnoreCase(f.getName()) && existing.length() == f.length())) {
                                            duplicate = true; break;
                                        }
                                    }
                                    if (!duplicate) availableKmzFiles.add(f);
                                } else if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("cache")) {
                                    // Escaneo 1 nivel adentro de subcarpetas
                                    File[] subList = f.listFiles((d, name) -> name != null && name.toLowerCase().endsWith(".kmz") && !name.startsWith("pure_dji_") && !name.startsWith("rc2_dl_"));
                                    if (subList != null) {
                                        for (File sf : subList) {
                                            boolean dup = false;
                                            for (File existing : availableKmzFiles) {
                                                if (existing.getAbsolutePath().equalsIgnoreCase(sf.getAbsolutePath()) ||
                                                    (existing.getName().equalsIgnoreCase(sf.getName()) && existing.length() == sf.length())) {
                                                    dup = true; break;
                                                }
                                            }
                                            if (!dup) availableKmzFiles.add(sf);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // MediaStore scan
                    try {
                        Uri collection = MediaStore.Files.getContentUri("external");
                        String[] projection = {MediaStore.Files.FileColumns.DATA};
                        String selection = MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE '%.kmz'";
                        try (Cursor cursor = getContentResolver().query(collection, projection, selection, null, null)) {
                            if (cursor != null) {
                                int dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                                while (cursor.moveToNext()) {
                                    String path = cursor.getString(dataIdx);
                                    if (path != null) {
                                        File f = new File(path);
                                        if (f.exists() && !f.getName().startsWith("pure_dji_") && !f.getName().startsWith("rc2_dl_")) {
                                            boolean dup = false;
                                            for (File existing : availableKmzFiles) {
                                                if (existing.getName().equalsIgnoreCase(f.getName())) {
                                                    dup = true; break;
                                                }
                                            }
                                            if (!dup) availableKmzFiles.add(f);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}

                    Collections.sort(availableKmzFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

                    JSONArray arr = new JSONArray();
                    for (File f : availableKmzFiles) {
                        JSONObject obj = new JSONObject();
                        obj.put("name", f.getName());
                        obj.put("path", f.getAbsolutePath());
                        obj.put("size", f.length());
                        obj.put("dateModified", f.lastModified());

                        String tag = "Teléfono";
                        if (f.getParent() != null) {
                            String parent = new File(f.getParent()).getName();
                            if (parent.equals("cache")) tag = "Compartido";
                            else if (parent.equalsIgnoreCase("Download")) tag = "Descargas";
                            else if (parent.equalsIgnoreCase("dronmxE")) tag = "dronmxE";
                            else if (parent.equalsIgnoreCase("helpers")) tag = "helpers";
                            else tag = parent;
                        }
                        obj.put("tag", tag);

                        List<WpmlKmzBuilder.Waypoint> wps = WpmlKmzBuilder.extractWaypoints(f);
                        obj.put("wpCount", wps.size());
                        JSONArray coordsArr = new JSONArray();
                        for (WpmlKmzBuilder.Waypoint wp : wps) {
                            JSONArray pt = new JSONArray();
                            pt.put(wp.lon);
                            pt.put(wp.lat);
                            coordsArr.put(pt);
                        }
                        obj.put("coords", coordsArr);
                        arr.put(obj);
                    }

                    // Scan RC2 Download folder via MTP
                    try {
                        List<MtpHelper.RemoteKmzFile> remoteKmzFiles = mtpHelper.scanDownloadKmzFiles();
                        for (MtpHelper.RemoteKmzFile rk : remoteKmzFiles) {
                            boolean alreadyHave = false;
                            for (File f : availableKmzFiles) {
                                if (f.getName().equalsIgnoreCase(rk.name)) { alreadyHave = true; break; }
                            }
                            if (alreadyHave) continue;

                            JSONObject obj = new JSONObject();
                            obj.put("name", rk.name);
                            obj.put("path", "mtp://rc2/Download/" + rk.name);
                            obj.put("size", rk.size);
                            obj.put("dateModified", rk.dateModified);
                            obj.put("tag", "RC2-Download");
                            obj.put("remoteHandle", rk.handle);

                            if (rk.size > 0 && rk.size < 3000000) {
                                byte[] data = mtpHelper.readRemoteBytes(rk.handle, (int) rk.size);
                                if (data != null && data.length > 0) {
                                    MtpHelper.KmzParsedInfo pi = mtpHelper.parseKmzFast(data);
                                    obj.put("wpCount", pi.wpCount);
                                    JSONArray cArr = new JSONArray();
                                    for (double[] pt : pi.coords) {
                                        JSONArray p = new JSONArray();
                                        p.put(pt[0]);
                                        p.put(pt[1]);
                                        cArr.put(p);
                                    }
                                    obj.put("coords", cArr);
                                }
                            }
                            arr.put(obj);
                        }
                    } catch (Exception ignored) {}

                    return arr.toString();
                }
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void fetchLocalMissionsAsync() {
            new Thread(() -> {
                String json = getLocalMissionsJson();
                notifyJs("onLocalMissionsLoaded(" + json + ");");
            }).start();
        }

        private File resolveTargetKmzFile(String localFileNameOrPath) {
            if (localFileNameOrPath == null || localFileNameOrPath.trim().isEmpty()) return null;
            String clean = localFileNameOrPath.trim();

            // 1. Ruta directa
            File direct = new File(clean);
            if (direct.exists() && direct.isFile()) return direct;

            // 2. Buscar en availableKmzFiles
            synchronized (availableKmzFiles) {
                for (File f : availableKmzFiles) {
                    if (f.getAbsolutePath().equalsIgnoreCase(clean) || f.getName().equalsIgnoreCase(clean)) {
                        return f;
                    }
                }
            }

            // 3. Fallback exhaustivo de carpetas comunes
            File ext = Environment.getExternalStorageDirectory();
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File[] candidates = new File[]{
                    new File(dl, clean),
                    new File(new File(dl, "dronmxE"), clean),
                    new File(new File(ext, "helpers"), clean),
                    new File(new File(ext, "DJI"), clean),
                    new File(new File(ext, "dronmxE"), clean),
                    new File("/sdcard/Download/" + clean),
                    new File("/sdcard/Download/dronmxE/" + clean),
                    new File("/sdcard/helpers/" + clean),
                    new File("/sdcard/DJI/" + clean),
                    new File("/sdcard/dronmxE/" + clean)
            };
            for (File c : candidates) {
                if (c.exists() && c.isFile()) return c;
            }
            return null;
        }

        @JavascriptInterface
        public boolean overwriteSlotFromLocal(String slotGuid, String localFileName) {
            new Thread(() -> {
                File targetFile = null;
                File tempDownloaded = null;

                try {
                    if (localFileName != null && localFileName.startsWith("mtp://")) {
                        onLog("[MTP] Descargando misión remota desde RC 2: " + localFileName);
                        String remoteName = localFileName.replace("mtp://rc2/Download/", "").trim();
                        List<MtpHelper.RemoteKmzFile> remoteList = mtpHelper.scanDownloadKmzFiles();
                        int remoteHandle = -1;
                        long remoteSize = 0;
                        for (MtpHelper.RemoteKmzFile rk : remoteList) {
                            if (rk.name.equalsIgnoreCase(remoteName)) {
                                remoteHandle = rk.handle;
                                remoteSize = rk.size;
                                break;
                            }
                        }
                        if (remoteHandle > 0 && remoteSize > 0) {
                            byte[] data = mtpHelper.readRemoteBytes(remoteHandle, (int) remoteSize);
                            if (data != null && data.length > 0) {
                                String cleanFileName = remoteName.endsWith(".kmz") ? remoteName : (remoteName + ".kmz");
                                tempDownloaded = new File(getCacheDir(), cleanFileName);
                                try (FileOutputStream fos = new FileOutputStream(tempDownloaded)) {
                                    fos.write(data);
                                }
                                targetFile = tempDownloaded;
                                onLog("[OK] Misión remota " + remoteName + " descargada temporalmente (" + data.length + " bytes)");
                            }
                        }
                    }

                    if (targetFile == null) {
                        targetFile = resolveTargetKmzFile(localFileName);
                    }

                    if (targetFile == null || !targetFile.exists()) {
                        runOnUiThread(() -> showToast("Archivo no encontrado: " + localFileName));
                        onLog("[ERR] Archivo no encontrado para sobreescribir: " + localFileName);
                        return;
                    }

                    onLog("[MTP] Iniciando inyección de " + targetFile.getName() + " hacia ranura " + slotGuid);
                    boolean ok = mtpHelper.overwriteMissionKmz(slotGuid, targetFile);
                    runOnUiThread(() -> {
                        if (ok) {
                            triggerHaptic();
                            showToast("🎉 ¡Misión inyectada con éxito en RC 2!");
                            notifyJs("refreshDeviceSlots();");
                        } else {
                            showToast("Error en la transferencia MTP. Revisa el log.");
                        }
                    });
                } catch (Exception e) {
                    onLog("[ERR] Excepción sobreescribiendo ranura: " + e.getMessage());
                } finally {
                    if (tempDownloaded != null && tempDownloaded.exists()) {
                        try { tempDownloaded.delete(); } catch (Exception ignored) {}
                    }
                }
            }).start();
            return true;
        }

        @JavascriptInterface
        public boolean createNewSlotFromLocal(String localFileName) {
            new Thread(() -> {
                File targetFile = null;
                File tempDownloaded = null;

                try {
                    if (localFileName != null && localFileName.startsWith("mtp://")) {
                        onLog("[MTP] Descargando misión remota desde RC 2: " + localFileName);
                        String remoteName = localFileName.replace("mtp://rc2/Download/", "").trim();
                        List<MtpHelper.RemoteKmzFile> remoteList = mtpHelper.scanDownloadKmzFiles();
                        int remoteHandle = -1;
                        long remoteSize = 0;
                        for (MtpHelper.RemoteKmzFile rk : remoteList) {
                            if (rk.name.equalsIgnoreCase(remoteName)) {
                                remoteHandle = rk.handle;
                                remoteSize = rk.size;
                                break;
                            }
                        }
                        if (remoteHandle > 0 && remoteSize > 0) {
                            byte[] data = mtpHelper.readRemoteBytes(remoteHandle, (int) remoteSize);
                            if (data != null && data.length > 0) {
                                String cleanFileName = remoteName.endsWith(".kmz") ? remoteName : (remoteName + ".kmz");
                                tempDownloaded = new File(getCacheDir(), cleanFileName);
                                try (FileOutputStream fos = new FileOutputStream(tempDownloaded)) {
                                    fos.write(data);
                                }
                                targetFile = tempDownloaded;
                            }
                        }
                    }

                    if (targetFile == null) {
                        targetFile = resolveTargetKmzFile(localFileName);
                    }

                    if (targetFile == null || !targetFile.exists()) {
                        runOnUiThread(() -> showToast("Archivo no encontrado: " + localFileName));
                        onLog("[ERR] Archivo no encontrado para crear nueva ranura: " + localFileName);
                        return;
                    }

                    onLog("[MTP] Creando nueva ranura en RC 2 desde: " + targetFile.getName());
                    boolean ok = mtpHelper.createNewMissionSlot(targetFile);
                    runOnUiThread(() -> {
                        if (ok) {
                            triggerHaptic();
                            showToast("🎉 ¡Nueva misión creada en RC 2!");
                            notifyJs("refreshDeviceSlots();");
                        } else {
                            showToast("Error al crear la misión en RC 2. Revisa el log.");
                        }
                    });
                } catch (Exception e) {
                    onLog("[ERR] Excepción creando ranura: " + e.getMessage());
                } finally {
                    if (tempDownloaded != null && tempDownloaded.exists()) {
                        try { tempDownloaded.delete(); } catch (Exception ignored) {}
                    }
                }
            }).start();
            return true;
        }

        @JavascriptInterface
        public void downloadDeviceSlot(String slotGuid) {
            new Thread(() -> {
                List<MtpHelper.DeviceSlotInfo> slots = mtpHelper.getDeviceSlotsDetailed();
                int kmzHandle = -1;
                long size = 0;
                for (MtpHelper.DeviceSlotInfo s : slots) {
                    if (s.guid.equalsIgnoreCase(slotGuid)) {
                        kmzHandle = s.kmzHandle;
                        size = s.size;
                        break;
                    }
                }

                if (kmzHandle <= 0) {
                    showToast("No se encontró el archivo KMZ en el control.");
                    return;
                }

                String b64 = mtpHelper.readSlotKmzBase64(kmzHandle, (int) size);
                if (b64 != null && !b64.isEmpty()) {
                    try {
                        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!downloads.exists()) downloads.mkdirs();
                        File out = new File(downloads, slotGuid + ".kmz");
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(bytes);
                        }
                        showToast("✅ Misión descargada a /Download/" + slotGuid + ".kmz");
                        // Asegurar compatibilidad universal (doc.kml para Google Earth y wpmz para DJI Fly)
                        try {
                            WpmlKmzBuilder.ensureDjiWpmlKmz(out, downloads);
                        } catch (Exception ignored) {}
                        notifyJs("refreshLocalMissions();");
                    } catch (Exception e) {
                        showToast("Error guardando misión: " + e.getMessage());
                    }
                } else {
                    showToast("Fallo al descargar KMZ desde el control.");
                }
            }).start();
        }

        @JavascriptInterface
        public void openInGoogleEarth(String fileNameOrGuid) {
            new Thread(() -> {
                File fileToOpen = null;
                synchronized (availableKmzFiles) {
                    for (File f : availableKmzFiles) {
                        if (f.getName().equalsIgnoreCase(fileNameOrGuid) || f.getName().equalsIgnoreCase(fileNameOrGuid + ".kmz")) {
                            fileToOpen = f;
                            break;
                        }
                    }
                }
                if (fileToOpen == null) {
                    File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File candidate = new File(downloads, fileNameOrGuid.endsWith(".kmz") ? fileNameOrGuid : (fileNameOrGuid + ".kmz"));
                    if (candidate.exists()) fileToOpen = candidate;
                }

                if (fileToOpen == null || !fileToOpen.exists()) {
                    showToast("No se encontró el archivo: " + fileNameOrGuid);
                    return;
                }

                try {
                    // Asegurar que contenga doc.kml para Google Earth y wpmz/ para DJI Fly
                    File universalFile = WpmlKmzBuilder.ensureDjiWpmlKmz(fileToOpen, getCacheDir());

                    Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this,
                            getPackageName() + ".fileprovider",
                            universalFile
                    );

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(contentUri, "application/vnd.google-earth.kmz");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    // Intentar abrir con Google Earth si está instalado
                    intent.setPackage("com.google.earth");
                    try {
                        startActivity(intent);
                        showToast("Abriendo en Google Earth...");
                    } catch (Exception e1) {
                        // Si falla directo al package, abrir selector general de apps
                        intent.setPackage(null);
                        Intent chooser = Intent.createChooser(intent, "Abrir misión KMZ con");
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(chooser);
                    }
                } catch (Exception e) {
                    showToast("Error abriendo en Google Earth: " + e.getMessage());
                }
            }).start();
        }

        @JavascriptInterface
        public void exportUniversalKmz(String fileName) {
            new Thread(() -> {
                File targetFile = null;
                synchronized (availableKmzFiles) {
                    for (File f : availableKmzFiles) {
                        if (f.getName().equalsIgnoreCase(fileName)) {
                            targetFile = f; break;
                        }
                    }
                }
                if (targetFile == null) {
                    File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File candidate = new File(downloads, fileName);
                    if (candidate.exists()) targetFile = candidate;
                }
                if (targetFile == null || !targetFile.exists()) {
                    showToast("Archivo no encontrado: " + fileName);
                    return;
                }
                try {
                    File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File result = WpmlKmzBuilder.ensureDjiWpmlKmz(targetFile, downloads);
                    showToast("✅ KMZ Universal exportado: " + result.getName());
                    notifyJs("refreshLocalMissions();");
                } catch (Exception e) {
                    showToast("Error exportando: " + e.getMessage());
                }
            }).start();
        }

        @JavascriptInterface
        public void refreshUsb() {
            new Thread(() -> {
                if (mtpHelper != null) {
                    mtpHelper.forceReconnect();
                    try { Thread.sleep(1200); } catch (Exception ignored) {}
                    fetchDeviceSlotsAsync();
                }
            }).start();
        }

        @JavascriptInterface
        public void setCustomRc2Path(String pathStr) {
            if (mtpHelper != null) {
                mtpHelper.setCustomWaypointPath(pathStr);
            }
        }

        @JavascriptInterface
        public String getCustomRc2Path() {
            return (mtpHelper != null) ? mtpHelper.getCustomWaypointPath() : "";
        }

        @JavascriptInterface
        public void pickFile() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = {"application/vnd.google-earth.kmz", "application/zip", "application/octet-stream"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            filePickerLauncher.launch(intent);
        }


        @JavascriptInterface
        public boolean deleteLocalMission(String localFileName) {
            boolean deletedAny = false;
            List<File> toRemove = new ArrayList<>();

            List<File> searchDirs = new ArrayList<>();
            searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
            searchDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dronmxE"));
            searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
            searchDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "dronmxE"));
            searchDirs.add(getExternalFilesDir(null));
            searchDirs.add(getCacheDir());

            for (File dir : searchDirs) {
                if (dir != null && dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles((d, name) -> name != null && name.equalsIgnoreCase(localFileName));
                    if (files != null) {
                        for (File f : files) {
                            if (f.exists()) {
                                if (f.delete()) {
                                    deletedAny = true;
                                    toRemove.add(f);
                                }
                            }
                        }
                    }
                }
            }

            synchronized (availableKmzFiles) {
                for (File f : availableKmzFiles) {
                    if (f.getName().equalsIgnoreCase(localFileName)) {
                        if (f.exists()) f.delete();
                        toRemove.add(f);
                    }
                }
                availableKmzFiles.removeAll(toRemove);
            }

            try {
                Uri collection = MediaStore.Files.getContentUri("external");
                getContentResolver().delete(collection, MediaStore.Files.FileColumns.DISPLAY_NAME + "=?", new String[]{localFileName});
            } catch (Exception ignored) {}

            if (deletedAny || toRemove.size() > 0) {
                showToast("🗑️ Archivo eliminado.");
                triggerHaptic();
                notifyJs("refreshLocalMissions();");
                return true;
            }
            return false;
        }

        @JavascriptInterface
        public boolean deleteDeviceSlot(String slotGuid) {
            new Thread(() -> {
                boolean ok = mtpHelper.deleteDeviceSlot(slotGuid);
                runOnUiThread(() -> {
                    if (ok) {
                        triggerHaptic();
                        showToast("🗑️ Misión eliminada del RC 2.");
                        notifyJs("refreshDeviceSlots();");
                    } else {
                        showToast("Fallo al eliminar misión del RC 2.");
                    }
                });
            }).start();
            return true;
        }

        @JavascriptInterface
        public void showToast(String msg) {
            MainActivity.this.showToast(msg);
        }

        @JavascriptInterface
        public void vibrate() {
            triggerHaptic();
        }
    }
}
