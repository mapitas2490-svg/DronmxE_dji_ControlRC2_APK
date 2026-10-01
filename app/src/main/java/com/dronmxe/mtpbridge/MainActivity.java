package com.dronmxe.mtpbridge;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MtpHelper.LogCallback {

    private WebView webView;
    private MtpHelper mtpHelper;
    private final List<File> availableKmzFiles = new ArrayList<>();

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        handleSelectedUri(uri);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        webView.addJavascriptInterface(new BridgeInterface(), "AndroidBridge");

        webView.setWebChromeClient(new android.webkit.WebChromeClient());
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                notifyJs("refreshAll();");
            }
        });

        mtpHelper = new MtpHelper(this, this);

        webView.loadUrl("file:///android_asset/index.html");

        // Handle incoming intent
        handleIncomingIntent(getIntent());

        // Connect USB
        mtpHelper.findAndConnectDevice();
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
                handleSelectedUri(uri);
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            if (uri != null) {
                handleSelectedUri(uri);
            }
        }
    }

    private void handleSelectedUri(Uri uri) {
        try {
            String displayName = "mision_recibida.kmz";
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) displayName = cursor.getString(nameIndex);
                cursor.close();
            }

            File tempFile = new File(getCacheDir(), displayName);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            if (!availableKmzFiles.contains(tempFile)) {
                availableKmzFiles.add(0, tempFile);
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

    private void notifyJs(String script) {
        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript(script, null));
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
                    arr.put(obj);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getLocalMissionsJson() {
            try {
                availableKmzFiles.clear();
                List<File> searchDirs = new ArrayList<>();
                searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
                searchDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "dronmxE"));
                searchDirs.add(new File("/sdcard/Download"));
                searchDirs.add(new File("/sdcard/Download/dronmxE"));
                searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
                searchDirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "dronmxE"));
                searchDirs.add(new File("/sdcard/Documents"));
                searchDirs.add(getExternalFilesDir(null));

                for (File dir : searchDirs) {
                    if (dir != null && dir.exists() && dir.isDirectory()) {
                        File[] list = dir.listFiles((d, name) -> name != null && name.toLowerCase().endsWith(".kmz"));
                        if (list != null) {
                            for (File f : list) {
                                boolean duplicate = false;
                                for (File existing : availableKmzFiles) {
                                    if (existing.getName().equalsIgnoreCase(f.getName())) {
                                        duplicate = true;
                                        break;
                                    }
                                }
                                if (!duplicate) {
                                    availableKmzFiles.add(f);
                                }
                            }
                        }
                    }
                }

                Collections.sort(availableKmzFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

                JSONArray arr = new JSONArray();
                for (File f : availableKmzFiles) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", f.getName());
                    obj.put("path", f.getAbsolutePath());
                    obj.put("size", f.length());
                    obj.put("dateModified", f.lastModified());

                    String tag = "dronmxE";
                    if (f.getParent() != null) {
                        tag = new File(f.getParent()).getName();
                    }
                    obj.put("tag", tag);

                    // Read first 3MB base64 for waypoint preview parser
                    if (f.length() < 3500000) {
                        try (FileInputStream fis = new FileInputStream(f)) {
                            byte[] data = new byte[(int) f.length()];
                            fis.read(data);
                            obj.put("base64", Base64.encodeToString(data, Base64.NO_WRAP));
                        } catch (Exception ignored) {}
                    }
                    arr.put(obj);
                }

                // --- Also scan RC2 Download folder via MTP ---
                try {
                    List<MtpHelper.RemoteKmzFile> remoteKmzFiles = mtpHelper.scanDownloadKmzFiles();
                    for (MtpHelper.RemoteKmzFile rk : remoteKmzFiles) {
                        // Skip if already in local list (same name)
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

                        // Read KMZ bytes via MTP for base64 (limit 3MB)
                        if (rk.size > 0 && rk.size < 3000000) {
                            String b64 = mtpHelper.readSlotKmzBase64(rk.handle, (int) rk.size);
                            if (b64 != null && !b64.isEmpty()) {
                                obj.put("base64", b64);
                            }
                        }
                        arr.put(obj);
                    }
                } catch (Exception ignored) {}

                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }


        @JavascriptInterface
        public boolean overwriteSlotFromLocal(String slotGuid, String localFileName) {
            File targetFile = null;
            for (File f : availableKmzFiles) {
                if (f.getName().equals(localFileName)) {
                    targetFile = f;
                    break;
                }
            }

            if (targetFile == null || !targetFile.exists()) {
                showToast("Archivo no encontrado: " + localFileName);
                return false;
            }

            final File fileToInject = targetFile;
            new Thread(() -> {
                boolean ok = mtpHelper.overwriteMissionKmz(slotGuid, fileToInject);
                runOnUiThread(() -> {
                    if (ok) {
                        triggerHaptic();
                        showToast("🎉 ¡Misión inyectada con éxito en RC 2!");
                        notifyJs("refreshDeviceSlots();");
                    } else {
                        showToast("Error en la transferencia MTP. Revisa el log.");
                    }
                });
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
                    showToast("No se encontró el archivo KMZ en la ranura seleccionada.");
                    return;
                }

                String b64 = mtpHelper.readSlotKmzBase64(kmzHandle, (int) size);
                if (b64 != null && !b64.isEmpty()) {
                    try {
                        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        File out = new File(downloads, slotGuid + ".kmz");
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(bytes);
                        }
                        showToast("✅ Misión descargada a /Download/" + slotGuid + ".kmz");
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
        public void refreshUsb() {
            mtpHelper.findAndConnectDevice();
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
        public boolean createNewSlotFromLocal(String localFileName) {
            File targetFile = null;
            for (File f : availableKmzFiles) {
                if (f.getName().equals(localFileName)) {
                    targetFile = f;
                    break;
                }
            }

            if (targetFile == null || !targetFile.exists()) {
                showToast("Archivo no encontrado: " + localFileName);
                return false;
            }

            final File fileToInject = targetFile;
            new Thread(() -> {
                boolean ok = mtpHelper.createNewMissionSlot(fileToInject);
                runOnUiThread(() -> {
                    if (ok) {
                        triggerHaptic();
                        showToast("🎉 ¡Nueva misión creada con éxito en RC 2!");
                        notifyJs("refreshDeviceSlots();");
                    } else {
                        showToast("Error creando nueva misión MTP. Revisa el log.");
                    }
                });
            }).start();
            return true;
        }

        @JavascriptInterface
        public boolean deleteLocalMission(String localFileName) {
            File targetFile = null;
            for (File f : availableKmzFiles) {
                if (f.getName().equalsIgnoreCase(localFileName)) {
                    targetFile = f;
                    break;
                }
            }
            if (targetFile != null && targetFile.exists()) {
                boolean deleted = targetFile.delete();
                if (deleted) {
                    showToast("🗑️ Archivo " + localFileName + " eliminado.");
                    availableKmzFiles.remove(targetFile);
                    notifyJs("refreshLocalMissions();");
                    return true;
                }
            }
            showToast("No se pudo eliminar: " + localFileName);
            return false;
        }

        @JavascriptInterface
        public boolean deleteDeviceSlot(String slotGuid) {
            new Thread(() -> {
                boolean ok = mtpHelper.deleteDeviceSlot(slotGuid);
                runOnUiThread(() -> {
                    if (ok) {
                        triggerHaptic();
                        showToast("🗑️ Misión " + slotGuid + " eliminada del RC 2.");
                        notifyJs("refreshDeviceSlots();");
                    } else {
                        showToast("Fallo al eliminar ranura del RC 2.");
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
