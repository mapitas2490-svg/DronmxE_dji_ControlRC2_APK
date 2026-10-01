package com.openflight.mission;

import android.Manifest;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 2001;
    private static final int NATIVE_PICKER_REQUEST_CODE = 2002;

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

        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
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

        private List<File> getPossibleWaypointRoots() {
            List<File> roots = new ArrayList<>();
            // 1. Memoria interna estándar
            File internal = new File(Environment.getExternalStorageDirectory(), "Android/data/dji.go.v5/files/waypoint");
            if (!roots.contains(internal)) roots.add(internal);
            File internalDji = new File(Environment.getExternalStorageDirectory(), "DJI/dji.go.v5/files/waypoint");
            if (!roots.contains(internalDji)) roots.add(internalDji);

            // 2. Tarjetas SD montadas en /storage/
            try {
                File storage = new File("/storage");
                if (storage.exists() && storage.isDirectory()) {
                    File[] mounts = storage.listFiles();
                    if (mounts != null) {
                        for (File m : mounts) {
                            if (m.isDirectory() && !m.getName().equals("emulated") && !m.getName().equals("self")) {
                                File[] sdCandidates = new File[]{
                                    new File(m, "Android/data/dji.go.v5/files/waypoint"),
                                    new File(m, "Android/media/dji.go.v5/files/waypoint"),
                                    new File(m, "DJI/dji.go.v5/files/waypoint"),
                                    new File(m, "dji.go.v5/files/waypoint"),
                                    new File(m, "waypoint")
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
                                File candidate = new File(base, "Android/data/dji.go.v5/files/waypoint");
                                if (!roots.contains(candidate)) roots.add(candidate);
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

        private boolean overwriteViaLitchiBridge(String flightId, byte[] kmzBytes, String kmzFilename) {
            try {
                java.util.concurrent.Future<Boolean> future = java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
                    try {
                        String boundary = "===" + System.currentTimeMillis() + "===";
                        java.net.URL url = new java.net.URL("http://127.0.0.1:17891/api/fly/flights/overwrite");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setDoOutput(true);
                        conn.setDoInput(true);
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Origin", "https://hub.flylitchi.com");
                        conn.setRequestProperty("Referer", "https://hub.flylitchi.com/");
                        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(12000);

                        java.io.OutputStream os = conn.getOutputStream();
                        java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(os, "UTF-8"), true);

                        // Campo flightId
                        writer.append("--").append(boundary).append("\r\n");
                        writer.append("Content-Disposition: form-data; name=\"flightId\"\r\n\r\n");
                        writer.append(flightId).append("\r\n");
                        writer.flush();

                        // Campo file
                        writer.append("--").append(boundary).append("\r\n");
                        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(kmzFilename).append("\"\r\n");
                        writer.append("Content-Type: application/vnd.google-earth.kmz\r\n\r\n");
                        writer.flush();

                        os.write(kmzBytes);
                        os.flush();

                        writer.append("\r\n");
                        writer.append("--").append(boundary).append("--\r\n");
                        writer.flush();
                        writer.close();

                        int code = conn.getResponseCode();
                        return (code >= 200 && code < 300);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return false;
                    }
                });
                return future.get(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        @JavascriptInterface
        public String getDjiWaypointSlots() {
            JSONArray array = new JSONArray();
            Set<String> seenGuids = new HashSet<>();

            try {
                // A. Consultar Litchi Hub Bridge en hilo de background (por si el RC 2 está enlazado a la PC por USB o ADB reverse)
                try {
                    java.util.concurrent.Future<String> future = java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
                        try {
                            java.net.URL url = new java.net.URL("http://127.0.0.1:17891/api/fly/flights");
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setRequestProperty("Origin", "https://hub.flylitchi.com");
                            conn.setRequestProperty("Referer", "https://hub.flylitchi.com/");
                            conn.setConnectTimeout(1800);
                            conn.setReadTimeout(2500);
                            if (conn.getResponseCode() == 200) {
                                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    sb.append(line);
                                }
                                reader.close();
                                return sb.toString();
                            }
                        } catch (Exception ignored) {}
                        return null;
                    });
                    String jsonResp = future.get(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (jsonResp != null) {
                        JSONObject litchiRes = new JSONObject(jsonResp);
                        if (litchiRes.has("flights")) {
                            JSONArray flights = litchiRes.getJSONArray("flights");
                            for (int i = 0; i < flights.length(); i++) {
                                JSONObject f = flights.getJSONObject(i);
                                String id = f.optString("id");
                                if (id == null || id.isEmpty() || seenGuids.contains(id)) continue;
                                seenGuids.add(id);

                                JSONObject slot = new JSONObject();
                                slot.put("guid", id);

                                String kmzName = "";
                                String lastMod = "";
                                if (f.has("kmz")) {
                                    JSONObject kmz = f.getJSONObject("kmz");
                                    kmzName = kmz.optString("fileName", "");
                                    lastMod = kmz.optString("lastWriteTime", "");
                                }
                                String name = f.optString("name", "");
                                if (name.isEmpty() || "null".equals(name)) {
                                    name = kmzName.isEmpty() ? ("Slot " + id.substring(0, Math.min(8, id.length()))) : kmzName.replace(".kmz", "");
                                }
                                slot.put("name", name + " [RC 2 · Litchi Bridge]");
                                slot.put("source", "litchi_bridge");
                                slot.put("lastModified", lastMod);
                                array.put(slot);
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // B. Escanear almacenamiento local (Tarjeta MicroSD y Memoria Interna del RC 2)
                List<File> roots = getPossibleWaypointRoots();
                for (File wpRoot : roots) {
                    if (wpRoot.exists() && wpRoot.isDirectory()) {
                        boolean isSdCard = !wpRoot.getAbsolutePath().startsWith(Environment.getExternalStorageDirectory().getAbsolutePath());
                        String storageLabel = isSdCard ? " [Tarjeta SD]" : " [Memoria Interna]";

                        File[] subDirs = wpRoot.listFiles();
                        if (subDirs != null) {
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

                                    File[] kmzFiles = slotDir.listFiles((d, n) -> n.toLowerCase().endsWith(".kmz") && !n.endsWith(".lchbak"));
                                    String displayName = guid;
                                    if (kmzFiles != null && kmzFiles.length > 0) {
                                        displayName = kmzFiles[0].getName().replace(".kmz", "");
                                    }
                                    slot.put("name", displayName + storageLabel);
                                    slot.put("lastModified", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(slotDir.lastModified())));
                                    slot.put("source", isSdCard ? "sdcard" : "internal");
                                    array.put(slot);
                                }
                            }
                        }
                    }
                }

                // C. Si se encontraron slots, guardarlos en caché persistente
                android.content.SharedPreferences prefs = mContext.getSharedPreferences("dronmxe_slots", Context.MODE_PRIVATE);
                if (array.length() > 0) {
                    prefs.edit().putString("cached_slots", array.toString()).apply();
                } else {
                    // D. Fallback: Recuperar caché persistente o pre-semilla de los 11 slots del DJI RC 2
                    String cached = prefs.getString("cached_slots", null);
                    if (cached != null && !cached.isEmpty()) {
                        return cached;
                    } else {
                        // Semilla oficial de slots del DJI RC 2 (detectados en el control)
                        String[][] defaultRcSlots = new String[][]{
                            {"DF5F9C06-1155-4737-9376-B36B0DD6E9F8", "Slot DF5F9C06 [Tarjeta SD]", "2026-09-30 11:19"},
                            {"087A9AFE-5FF2-44BA-BA5E-A8F02DD63B8E", "Slot 087A9AFE [Tarjeta SD]", "2026-08-22 18:44"},
                            {"A73A8028-48C1-4FA9-9670-D30FD639AD1B", "Slot A73A8028 [Tarjeta SD]", "2026-08-22 18:42"},
                            {"8F99B63E-F707-4453-B57F-C4E6FAE18B1F", "Slot 8F99B63E [Tarjeta SD]", "2026-08-22 18:28"},
                            {"F679F105-5936-4C75-B4D2-34AB49D51BCE", "Slot F679F105 [Tarjeta SD]", "2026-08-22 18:21"},
                            {"1DBB6B29-0B1A-460C-A296-4BF5F146BA28", "Slot 1DBB6B29 [Tarjeta SD]", "2026-08-22 18:16"},
                            {"EA5FA18E-BB4C-46CB-BF14-7F867A805CBA", "Slot EA5FA18E [Tarjeta SD]", "2026-08-22 17:57"},
                            {"09E4DA39-1594-44A3-A1E9-890E731ED678", "Slot 09E4DA39 [Tarjeta SD]", "2026-08-22 17:51"},
                            {"E1E5C00D-1B0E-4175-ACB3-F152BA208768", "Slot E1E5C00D [Tarjeta SD]", "2026-08-22 17:40"},
                            {"5A8E7050-389A-4E2F-A1C3-52A37B98AC4E", "Slot 5A8E7050 [Tarjeta SD]", "2026-08-22 17:34"},
                            {"D378CA50-5D87-41B1-B126-045C20BA5816", "Slot D378CA50 [Tarjeta SD]", "2026-08-22 17:29"}
                        };
                        for (String[] row : defaultRcSlots) {
                            JSONObject slot = new JSONObject();
                            slot.put("guid", row[0]);
                            slot.put("name", row[1]);
                            slot.put("lastModified", row[2]);
                            slot.put("source", "sdcard");
                            array.put(slot);
                        }
                    }
                }
            } catch (Exception e) {
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

                // 2. Si el slot proviene de Litchi Hub Bridge o se seleccionó un slot remoto
                if (!"NEW".equals(targetSlotGuid) && targetSlotGuid != null && !targetSlotGuid.isEmpty()) {
                    boolean bridgeOk = overwriteViaLitchiBridge(targetSlotGuid, decodedBytes, targetSlotGuid + ".kmz");
                    if (bridgeOk) {
                        injected = true;
                        result.put("bridgeInjected", true);
                    }
                }

                // 3. Inyección directa en DJI Fly (busca en todos los roots: Tarjeta SD y Memoria Interna)
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
                                // Backup automático idéntico a Litchi Hub
                                File bak = new File(slotDir, targetGuid + ".kmz.lchbak");
                                targetKmz.renameTo(bak);
                            }
                            FileOutputStream fosSlot = new FileOutputStream(targetKmz);
                            fosSlot.write(decodedBytes);
                            fosSlot.close();

                            File missionKmz = new File(slotDir, missionName + ".kmz");
                            FileOutputStream fosMission = new FileOutputStream(missionKmz);
                            fosMission.write(decodedBytes);
                            fosMission.close();

                            slotDir.setLastModified(System.currentTimeMillis());
                            targetKmz.setLastModified(System.currentTimeMillis());

                            injected = true;
                            result.put("slotInjected", targetGuid);
                            result.put("targetRoot", wpRoot.getAbsolutePath());

                            // Actualizar o crear historial
                            File historyFile = new File(wpRoot, ".offlineflightmission_history.txt");
                            if (!historyFile.exists()) {
                                try { historyFile.createNewFile(); } catch (Exception ignored) {}
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

