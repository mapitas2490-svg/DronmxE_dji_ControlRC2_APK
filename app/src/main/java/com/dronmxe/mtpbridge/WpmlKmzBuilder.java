package com.dronmxe.mtpbridge;

import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Motor Generador y Convertidor de Paquetes KMZ WPML Estándar para DJI Fly (RC 2 / RC Pro).
 * Garantiza total compatibilidad bidireccional:
 * 1. Google Earth (doc.kml en la raíz para visualización 3D y lista de pines)
 * 2. DJI Fly / Pilot 2 (wpmz/template.kml y wpmz/waylines.wpml con ejecución completa de waypoints, velocidades, alturas y acciones)
 */
public class WpmlKmzBuilder {

    private static final String TAG = "WpmlKmzBuilder";

    public static class Waypoint {
        public double lon;
        public double lat;
        public double alt = 50.0;
        public double speed = 8.0;

        public Waypoint(double lon, double lat, double alt) {
            this.lon = lon;
            this.lat = lat;
            this.alt = alt > 0 ? alt : 50.0;
        }

        public Waypoint(double lon, double lat, double alt, double speed) {
            this.lon = lon;
            this.lat = lat;
            this.alt = alt > 0 ? alt : 50.0;
            this.speed = speed > 0 ? speed : 8.0;
        }
    }

    private static void logD(String tag, String msg) {
        try { android.util.Log.d(tag, msg); } catch (Throwable t) { System.out.println("[" + tag + "] " + msg); }
    }
    private static void logW(String tag, String msg) {
        try { android.util.Log.w(tag, msg); } catch (Throwable t) { System.err.println("[" + tag + "] " + msg); }
    }
    private static void logE(String tag, String msg, Throwable e) {
        try { android.util.Log.e(tag, msg, e); } catch (Throwable t) { System.err.println("[" + tag + "] " + msg + " " + e); }
    }

    private static String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }

    public static double haversineMeters(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371000.0 * c;
    }

    /**
     * Procesa un archivo KMZ/KML local y devuelve un archivo KMZ universal compatible con:
     * 1. Google Earth (doc.kml en la raíz)
     * 2. DJI Fly RC 2 / RC Pro (wpmz/template.kml y wpmz/waylines.wpml con información de vuelo completa)
     */
    public static File ensureDjiWpmlKmz(File inputFile, File outputDir) {
        if (inputFile == null || !inputFile.exists()) return inputFile;

        try {
            // 1. Verificar si ya es un paquete universal válido (contiene waylines.wpml con waypoints Y doc.kml)
            if (isAlreadyUniversal(inputFile)) {
                logD(TAG, "El archivo ya es un paquete universal válido (DJI Fly + Google Earth): " + inputFile.getName());
                return inputFile;
            }

            logD(TAG, "Generando KMZ universal compatible con Google Earth y DJI Fly para: " + inputFile.getName());

            // 2. Extraer waypoints del archivo
            List<Waypoint> waypoints = extractWaypoints(inputFile);
            if (waypoints.isEmpty()) {
                logW(TAG, "No se encontraron waypoints en el archivo original. Devolviendo sin modificar.");
                return inputFile;
            }

            String missionName = inputFile.getName().replace(".kmz", "").replace(".kml", "");
            File targetKmz = new File(outputDir, missionName + "_universal.kmz");

            // 3. Revisar si el archivo de origen ya tenía template.kml o waylines.wpml intactos
            byte[] origTemplateBytes = null;
            byte[] origWaylinesBytes = null;

            if (inputFile.getName().toLowerCase().endsWith(".kmz")) {
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(inputFile))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName().toLowerCase();
                        if (name.contains("template.kml")) {
                            origTemplateBytes = readAllBytes(zis);
                        } else if (name.contains("waylines.wpml")) {
                            origWaylinesBytes = readAllBytes(zis);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Validar si el waylines original contenía Placemarks válidos
            boolean origWaylinesValid = false;
            if (origWaylinesBytes != null) {
                String wStr = new String(origWaylinesBytes, StandardCharsets.UTF_8);
                if (wStr.contains("<Placemark>") && wStr.contains("<coordinates>") && wStr.contains("<wpml:executeHeight>")) {
                    origWaylinesValid = true;
                }
            }

            // Generar doc.kml para Google Earth
            String docKmlXml = generateDocKml(missionName, waypoints);

            // Generar XML para DJI Fly si no están o eran inválidos
            byte[] finalTemplateBytes;
            byte[] finalWaylinesBytes;

            if (origTemplateBytes != null && origWaylinesValid) {
                // Conservar los originales de DJI Fly e inyectar el nombre de misión si no lo tiene
                String tStr = new String(origTemplateBytes, StandardCharsets.UTF_8);
                if (!tStr.contains("<wpml:missionName>")) {
                    String folderBlock = "    <Folder>\n" +
                                         "      <wpml:templateType>waypoint</wpml:templateType>\n" +
                                         "      <wpml:templateId>0</wpml:templateId>\n" +
                                         "      <wpml:autoFlightSpeed>8.0</wpml:autoFlightSpeed>\n" +
                                         "      <wpml:missionName><![CDATA[" + escapeXml(missionName) + "]]></wpml:missionName>\n" +
                                         "    </Folder>\n" +
                                         "  </Document>";
                    if (tStr.contains("</Document>")) {
                        tStr = tStr.replace("</Document>", folderBlock);
                    }
                    finalTemplateBytes = tStr.getBytes(StandardCharsets.UTF_8);
                } else {
                    finalTemplateBytes = origTemplateBytes;
                }

                String wStr = new String(origWaylinesBytes, StandardCharsets.UTF_8);
                if (!wStr.contains("<wpml:missionName>")) {
                    if (wStr.contains("<Folder>")) {
                        wStr = wStr.replace("<Folder>", "<Folder>\n      <wpml:missionName><![CDATA[" + escapeXml(missionName) + "]]></wpml:missionName>");
                    }
                    finalWaylinesBytes = wStr.getBytes(StandardCharsets.UTF_8);
                } else {
                    finalWaylinesBytes = origWaylinesBytes;
                }
                logD(TAG, "Conservando wpmz/waylines.wpml y template.kml con nombre de misión: " + missionName);
            } else {
                // Generar paquete WPML 1.0.3/1.0.6 completo y validado
                String templateXml = generateTemplateKml(missionName, waypoints);
                String waylinesXml = generateWaylinesWpml(missionName, waypoints);
                finalTemplateBytes = templateXml.getBytes(StandardCharsets.UTF_8);
                finalWaylinesBytes = waylinesXml.getBytes(StandardCharsets.UTF_8);
                logD(TAG, "Generando wpmz/waylines.wpml con " + waypoints.size() + " waypoints completos de ejecución");
            }

            // 4. Empaquetar todo en el archivo .kmz universal
            try (FileOutputStream fos = new FileOutputStream(targetKmz);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // Entry 1: doc.kml en la raíz (estándar Google Earth)
                ZipEntry entryDocKml = new ZipEntry("doc.kml");
                zos.putNextEntry(entryDocKml);
                zos.write(docKmlXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                // Entry 2: wpmz/template.kml (encabezado oficial DJI Fly)
                ZipEntry entryTemplate = new ZipEntry("wpmz/template.kml");
                zos.putNextEntry(entryTemplate);
                zos.write(finalTemplateBytes);
                zos.closeEntry();

                // Entry 3: wpmz/waylines.wpml (instrucciones de vuelo y waypoints DJI Fly)
                ZipEntry entryWaylines = new ZipEntry("wpmz/waylines.wpml");
                zos.putNextEntry(entryWaylines);
                zos.write(finalWaylinesBytes);
                zos.closeEntry();

                zos.finish();
            }

            logD(TAG, "✅ Paquete Universal KMZ generado exitosamente: " + targetKmz.getAbsolutePath() + " (" + waypoints.size() + " WP)");
            return targetKmz;

        } catch (Exception e) {
            logE(TAG, "Error convirtiendo a paquete Universal KMZ: " + e.getMessage(), e);
            return inputFile;
        }
    }

    /**
     * Genera un paquete KMZ nativo y puro para DJI Fly (RC 2 / RC Pro).
     * Contiene estrictamente wpmz/template.kml y wpmz/waylines.wpml (WPML 1.0.6),
     * sin archivos raíz como doc.kml, garantizando total compatibilidad nativa con
     * el indexador de misiones C++ de DJI Fly en el control remoto.
     */
    public static File buildPureDjiKmz(File inputFile, File outputDir, String missionName) {
        if (inputFile == null || !inputFile.exists()) return inputFile;

        try {
            byte[] origTemplateBytes = null;
            byte[] origWaylinesBytes = null;
            boolean origWaylinesValid = false;

            if (inputFile.getName().toLowerCase().endsWith(".kmz")) {
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(inputFile))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName().toLowerCase();
                        if (name.contains("waylines.wpml")) {
                            origWaylinesBytes = readAllBytes(zis);
                            String content = new String(origWaylinesBytes, StandardCharsets.UTF_8);
                            if (content.contains("<Placemark>") && content.contains("<coordinates>")) {
                                origWaylinesValid = true;
                            }
                        } else if (name.contains("template.kml")) {
                            origTemplateBytes = readAllBytes(zis);
                        }
                    }
                }
            }

            String safeName = (missionName != null && !missionName.trim().isEmpty()) ?
                    missionName.trim().replace(".kmz", "").replace(".kml", "") :
                    inputFile.getName().replace(".kmz", "").replace(".kml", "");

            File targetKmz = File.createTempFile("pure_dji_", ".kmz", outputDir);

            byte[] finalTemplateBytes;
            byte[] finalWaylinesBytes;

            if (origWaylinesValid && origWaylinesBytes != null) {
                // Conservar las waylines originales completas (con sus acciones de cámara, alturas y velocidades originales)
                String wStr = new String(origWaylinesBytes, StandardCharsets.UTF_8);
                if (wStr.contains("<wpml:missionName>")) {
                    wStr = wStr.replaceAll("<wpml:missionName>[\\s\\S]*?</wpml:missionName>",
                            "<wpml:missionName><![CDATA[" + escapeXml(safeName) + "]]></wpml:missionName>");
                } else if (wStr.contains("<Folder>")) {
                    wStr = wStr.replace("<Folder>", "<Folder>\n      <wpml:missionName><![CDATA[" + escapeXml(safeName) + "]]></wpml:missionName>");
                }
                finalWaylinesBytes = wStr.getBytes(StandardCharsets.UTF_8);

                if (origTemplateBytes != null && origTemplateBytes.length > 0) {
                    String tStr = new String(origTemplateBytes, StandardCharsets.UTF_8);
                    if (tStr.contains("<wpml:missionName>")) {
                        tStr = tStr.replaceAll("<wpml:missionName>[\\s\\S]*?</wpml:missionName>",
                                "<wpml:missionName><![CDATA[" + escapeXml(safeName) + "]]></wpml:missionName>");
                    } else if (tStr.contains("<Folder>")) {
                        tStr = tStr.replace("<Folder>", "<Folder>\n      <wpml:templateType>waypoint</wpml:templateType>\n      <wpml:missionName><![CDATA[" + escapeXml(safeName) + "]]></wpml:missionName>");
                    } else if (tStr.contains("</Document>")) {
                        String folderBlock = "    <Folder>\n" +
                                             "      <wpml:templateType>waypoint</wpml:templateType>\n" +
                                             "      <wpml:templateId>0</wpml:templateId>\n" +
                                             "      <wpml:autoFlightSpeed>8.0</wpml:autoFlightSpeed>\n" +
                                             "      <wpml:missionName><![CDATA[" + escapeXml(safeName) + "]]></wpml:missionName>\n" +
                                             "    </Folder>\n" +
                                             "  </Document>";
                        tStr = tStr.replace("</Document>", folderBlock);
                    }
                    finalTemplateBytes = tStr.getBytes(StandardCharsets.UTF_8);
                } else {
                    List<Waypoint> waypoints = extractWaypoints(inputFile);
                    finalTemplateBytes = generateTemplateKml(safeName, waypoints).getBytes(StandardCharsets.UTF_8);
                }
            } else {
                List<Waypoint> waypoints = extractWaypoints(inputFile);
                if (waypoints.isEmpty()) {
                    logW(TAG, "No se encontraron waypoints para buildPureDjiKmz en " + inputFile.getName());
                    return inputFile;
                }
                finalTemplateBytes = generateTemplateKml(safeName, waypoints).getBytes(StandardCharsets.UTF_8);
                finalWaylinesBytes = generateWaylinesWpml(safeName, waypoints).getBytes(StandardCharsets.UTF_8);
            }

            try (FileOutputStream fos = new FileOutputStream(targetKmz);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // Entry 1: wpmz/ (directorio requerido por DJI Fly)
                ZipEntry entryDir = new ZipEntry("wpmz/");
                zos.putNextEntry(entryDir);
                zos.closeEntry();

                // Entry 2: wpmz/template.kml
                ZipEntry entryTemplate = new ZipEntry("wpmz/template.kml");
                zos.putNextEntry(entryTemplate);
                zos.write(finalTemplateBytes);
                zos.closeEntry();

                // Entry 3: wpmz/waylines.wpml
                ZipEntry entryWaylines = new ZipEntry("wpmz/waylines.wpml");
                zos.putNextEntry(entryWaylines);
                zos.write(finalWaylinesBytes);
                zos.closeEntry();

                zos.finish();
            }

            logD(TAG, "✅ Paquete Pure DJI KMZ generado exitosamente: " + targetKmz.getAbsolutePath());
            return targetKmz;

        } catch (Exception e) {
            logE(TAG, "Error generando Pure DJI KMZ: " + e.getMessage(), e);
            return inputFile;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toByteArray();
    }

    public static boolean isAlreadyUniversal(File file) {
        if (!file.getName().toLowerCase().endsWith(".kmz")) return false;
        boolean hasValidWaylines = false;
        boolean hasTemplate = false;
        boolean hasDocKml = false;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.contains("waylines.wpml")) {
                    byte[] data = readAllBytes(zis);
                    String content = new String(data, StandardCharsets.UTF_8);
                    if (content.contains("<Placemark>") && content.contains("<coordinates>") && content.contains("<wpml:executeHeight>")) {
                        hasValidWaylines = true;
                    }
                } else if (name.contains("template.kml")) {
                    hasTemplate = true;
                } else if (name.equals("doc.kml") || name.endsWith("/doc.kml") || (name.endsWith(".kml") && !name.startsWith("wpmz/"))) {
                    hasDocKml = true;
                }
            }
        } catch (Exception ignored) {}

        return hasValidWaylines && hasTemplate && hasDocKml;
    }

    public static List<Waypoint> extractWaypoints(File file) throws Exception {
        List<Waypoint> result = new ArrayList<>();
        if (file == null || !file.exists()) return result;

        String waylinesXml = null;
        String templateXml = null;
        String genericKml = null;

        if (file.getName().toLowerCase().endsWith(".kmz")) {
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName().toLowerCase();
                    if (name.contains("waylines.wpml")) {
                        waylinesXml = new String(readAllBytes(zis), StandardCharsets.UTF_8);
                    } else if (name.contains("template.kml")) {
                        templateXml = new String(readAllBytes(zis), StandardCharsets.UTF_8);
                    } else if (name.endsWith(".kml") && !name.startsWith("wpmz/")) {
                        genericKml = new String(readAllBytes(zis), StandardCharsets.UTF_8);
                    }
                }
            }
        } else {
            try (FileInputStream fis = new FileInputStream(file)) {
                genericKml = new String(readAllBytes(fis), StandardCharsets.UTF_8);
            }
        }

        // Prioridad 1: Extraer de wpmz/waylines.wpml
        if (waylinesXml != null && waylinesXml.contains("<Placemark>")) {
            result = parseWpmlPlacemarks(waylinesXml);
            if (!result.isEmpty()) return result;
        }

        // Prioridad 2: Extraer de template.kml si tuviera placemarks
        if (templateXml != null && templateXml.contains("<Placemark>")) {
            result = parseWpmlPlacemarks(templateXml);
            if (!result.isEmpty()) return result;
        }

        // Prioridad 3: Extraer de doc.kml / KML genérico de Google Earth
        if (genericKml != null) {
            result = parseGenericKml(genericKml);
            if (!result.isEmpty()) return result;
        }

        return result;
    }

    private static List<Waypoint> parseWpmlPlacemarks(String xml) {
        List<Waypoint> list = new ArrayList<>();
        Pattern pmPattern = Pattern.compile("<Placemark[\\s\\S]*?</Placemark>", Pattern.CASE_INSENSITIVE);
        Pattern coordPattern = Pattern.compile("<coordinates>([\\s\\S]*?)</coordinates>", Pattern.CASE_INSENSITIVE);
        Pattern heightPattern = Pattern.compile("<wpml:executeHeight>([0-9.]+)</wpml:executeHeight>", Pattern.CASE_INSENSITIVE);
        Pattern speedPattern = Pattern.compile("<wpml:waypointSpeed>([0-9.]+)</wpml:waypointSpeed>", Pattern.CASE_INSENSITIVE);

        Matcher pmMatcher = pmPattern.matcher(xml);
        while (pmMatcher.find()) {
            String pm = pmMatcher.group();
            Matcher cMatcher = coordPattern.matcher(pm);
            if (cMatcher.find()) {
                String[] tokens = cMatcher.group(1).trim().split("[,\\s]+");
                if (tokens.length >= 2) {
                    try {
                        double lon = Double.parseDouble(tokens[0].trim());
                        double lat = Double.parseDouble(tokens[1].trim());
                        double alt = 50.0;
                        double speed = 8.0;

                        Matcher hMatcher = heightPattern.matcher(pm);
                        if (hMatcher.find()) {
                            alt = Double.parseDouble(hMatcher.group(1).trim());
                        } else if (tokens.length >= 3) {
                            alt = Double.parseDouble(tokens[2].trim());
                        }

                        Matcher sMatcher = speedPattern.matcher(pm);
                        if (sMatcher.find()) {
                            speed = Double.parseDouble(sMatcher.group(1).trim());
                        }

                        if (!Double.isNaN(lon) && !Double.isNaN(lat) && (lon != 0.0 || lat != 0.0)) {
                            list.add(new Waypoint(lon, lat, alt, speed));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return list;
    }

    private static List<Waypoint> parseGenericKml(String xml) {
        List<Waypoint> list = new ArrayList<>();

        // Intentar primero con Placemark de puntos individuales para no duplicar
        Pattern pmPattern = Pattern.compile("<Placemark[\\s\\S]*?</Placemark>", Pattern.CASE_INSENSITIVE);
        Pattern pointCoordPattern = Pattern.compile("<Point>[\\s\\S]*?<coordinates>([\\s\\S]*?)</coordinates>[\\s\\S]*?</Point>", Pattern.CASE_INSENSITIVE);
        Matcher pmMatcher = pmPattern.matcher(xml);

        while (pmMatcher.find()) {
            String pm = pmMatcher.group();
            Matcher ptMatcher = pointCoordPattern.matcher(pm);
            if (ptMatcher.find()) {
                String[] tokens = ptMatcher.group(1).trim().split("[,\\s]+");
                if (tokens.length >= 2) {
                    try {
                        double lon = Double.parseDouble(tokens[0].trim());
                        double lat = Double.parseDouble(tokens[1].trim());
                        double alt = (tokens.length >= 3) ? Double.parseDouble(tokens[2].trim()) : 50.0;
                        if (!Double.isNaN(lon) && !Double.isNaN(lat) && (lon != 0.0 || lat != 0.0)) {
                            list.add(new Waypoint(lon, lat, alt));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (!list.isEmpty()) return list;

        // Si no había Points individuales, buscar en LineString
        Pattern lsCoordPattern = Pattern.compile("<LineString>[\\s\\S]*?<coordinates>([\\s\\S]*?)</coordinates>[\\s\\S]*?</LineString>", Pattern.CASE_INSENSITIVE);
        Matcher lsMatcher = lsCoordPattern.matcher(xml);
        if (lsMatcher.find()) {
            String[] coordPairs = lsMatcher.group(1).trim().split("\\s+");
            for (String pair : coordPairs) {
                String[] parts = pair.split(",");
                if (parts.length >= 2) {
                    try {
                        double lon = Double.parseDouble(parts[0].trim());
                        double lat = Double.parseDouble(parts[1].trim());
                        double alt = (parts.length >= 3) ? Double.parseDouble(parts[2].trim()) : 50.0;
                        if (!Double.isNaN(lon) && !Double.isNaN(lat) && (lon != 0.0 || lat != 0.0)) {
                            list.add(new Waypoint(lon, lat, alt));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return list;
    }

    public static String generateTemplateKml(String missionName, List<Waypoint> waypoints) {
        long now = System.currentTimeMillis();
        String safeName = (missionName != null && !missionName.trim().isEmpty()) ? escapeXml(missionName.trim()) : "Misión dronmxE";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\">\n" +
               "  <Document>\n" +
               "    <wpml:author>dronmxE - Edgar</wpml:author>\n" +
               "    <wpml:createTime>" + now + "</wpml:createTime>\n" +
               "    <wpml:updateTime>" + now + "</wpml:updateTime>\n" +
               "    <wpml:missionConfig>\n" +
               "      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>\n" +
               "      <wpml:finishAction>goHome</wpml:finishAction>\n" +
               "      <wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>\n" +
               "      <wpml:executeRCLostAction>goHome</wpml:executeRCLostAction>\n" +
               "      <wpml:takeOffSecurityHeight>20</wpml:takeOffSecurityHeight>\n" +
               "      <wpml:globalTransitionalSpeed>8.0</wpml:globalTransitionalSpeed>\n" +
               "      <wpml:droneInfo>\n" +
               "        <wpml:droneEnumValue>68</wpml:droneEnumValue>\n" +
               "        <wpml:droneSubEnumValue>0</wpml:droneSubEnumValue>\n" +
               "      </wpml:droneInfo>\n" +
               "    </wpml:missionConfig>\n" +
               "    <Folder>\n" +
               "      <wpml:templateType>waypoint</wpml:templateType>\n" +
               "      <wpml:templateId>0</wpml:templateId>\n" +
               "      <wpml:autoFlightSpeed>8.0</wpml:autoFlightSpeed>\n" +
               "      <wpml:missionName><![CDATA[" + safeName + "]]></wpml:missionName>\n" +
               "    </Folder>\n" +
               "  </Document>\n" +
               "</kml>\n";
    }

    public static String generateWaylinesWpml(String missionName, List<Waypoint> waypoints) {
        double totalDist = 0.0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Waypoint w1 = waypoints.get(i);
            Waypoint w2 = waypoints.get(i + 1);
            totalDist += haversineMeters(w1.lon, w1.lat, w2.lon, w2.lat);
        }
        int totalSec = (int) Math.max(10, Math.round(totalDist / 8.0));
        String safeName = (missionName != null && !missionName.trim().isEmpty()) ? escapeXml(missionName.trim()) : "Misión dronmxE";

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\">\n");
        sb.append("  <Document>\n");
        sb.append("    <wpml:missionConfig>\n");
        sb.append("      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>\n");
        sb.append("      <wpml:finishAction>goHome</wpml:finishAction>\n");
        sb.append("      <wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>\n");
        sb.append("      <wpml:executeRCLostAction>goHome</wpml:executeRCLostAction>\n");
        sb.append("      <wpml:takeOffSecurityHeight>20</wpml:takeOffSecurityHeight>\n");
        sb.append("      <wpml:globalTransitionalSpeed>8.0</wpml:globalTransitionalSpeed>\n");
        sb.append("      <wpml:droneInfo>\n");
        sb.append("        <wpml:droneEnumValue>68</wpml:droneEnumValue>\n");
        sb.append("        <wpml:droneSubEnumValue>0</wpml:droneSubEnumValue>\n");
        sb.append("      </wpml:droneInfo>\n");
        sb.append("    </wpml:missionConfig>\n");
        sb.append("    <Folder>\n");
        sb.append("      <wpml:templateId>0</wpml:templateId>\n");
        sb.append("      <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>\n");
        sb.append("      <wpml:waylineId>0</wpml:waylineId>\n");
        sb.append("      <wpml:distance>").append((int) Math.round(totalDist)).append("</wpml:distance>\n");
        sb.append("      <wpml:duration>").append(totalSec).append("</wpml:duration>\n");
        sb.append("      <wpml:autoFlightSpeed>8.0</wpml:autoFlightSpeed>\n");
        sb.append("      <wpml:missionName><![CDATA[").append(safeName).append("]]></wpml:missionName>\n");

        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            String coordStr = String.format(Locale.US, "%.7f,%.7f", wp.lon, wp.lat);
            String heightStr = String.format(Locale.US, "%.2f", wp.alt);
            String speedStr = String.format(Locale.US, "%.1f", wp.speed > 0 ? wp.speed : 8.0);

            sb.append("      <Placemark>\n");
            sb.append("        <Point>\n");
            sb.append("          <coordinates>").append(coordStr).append("</coordinates>\n");
            sb.append("        </Point>\n");
            sb.append("        <wpml:index>").append(i).append("</wpml:index>\n");
            sb.append("        <wpml:executeHeight>").append(heightStr).append("</wpml:executeHeight>\n");
            sb.append("        <wpml:waypointSpeed>").append(speedStr).append("</wpml:waypointSpeed>\n");
            sb.append("        <wpml:waypointHeadingParam>\n");
            sb.append("          <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>\n");
            sb.append("          <wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle>\n");
            sb.append("          <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>\n");
            sb.append("          <wpml:waypointHeadingAngleEnable>0</wpml:waypointHeadingAngleEnable>\n");
            sb.append("          <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>\n");
            sb.append("          <wpml:waypointHeadingPoiIndex>0</wpml:waypointHeadingPoiIndex>\n");
            sb.append("        </wpml:waypointHeadingParam>\n");
            sb.append("        <wpml:waypointTurnParam>\n");
            sb.append("          <wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>\n");
            sb.append("          <wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>\n");
            sb.append("        </wpml:waypointTurnParam>\n");
            sb.append("        <wpml:useStraightLine>1</wpml:useStraightLine>\n");
            sb.append("        <wpml:actionGroup>\n");
            sb.append("          <wpml:actionGroupId>").append(i + 1).append("</wpml:actionGroupId>\n");
            sb.append("          <wpml:actionGroupStartIndex>").append(i).append("</wpml:actionGroupStartIndex>\n");
            sb.append("          <wpml:actionGroupEndIndex>").append(i).append("</wpml:actionGroupEndIndex>\n");
            sb.append("          <wpml:actionGroupMode>parallel</wpml:actionGroupMode>\n");
            sb.append("          <wpml:actionTrigger>\n");
            sb.append("            <wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>\n");
            sb.append("          </wpml:actionTrigger>\n");
            sb.append("          <wpml:action>\n");
            sb.append("            <wpml:actionId>1</wpml:actionId>\n");
            sb.append("            <wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>\n");
            sb.append("            <wpml:actionActuatorFuncParam>\n");
            sb.append("              <wpml:gimbalHeadingYawBase>aircraft</wpml:gimbalHeadingYawBase>\n");
            sb.append("              <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>\n");
            sb.append("              <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>\n");
            sb.append("              <wpml:gimbalPitchRotateAngle>-90</wpml:gimbalPitchRotateAngle>\n");
            sb.append("              <wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>\n");
            sb.append("              <wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle>\n");
            sb.append("              <wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>\n");
            sb.append("              <wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>\n");
            sb.append("              <wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>\n");
            sb.append("              <wpml:gimbalRotateTime>0</wpml:gimbalRotateTime>\n");
            sb.append("              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>\n");
            sb.append("            </wpml:actionActuatorFuncParam>\n");
            sb.append("          </wpml:action>\n");
            sb.append("          <wpml:action>\n");
            sb.append("            <wpml:actionId>2</wpml:actionId>\n");
            sb.append("            <wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>\n");
            sb.append("            <wpml:actionActuatorFuncParam>\n");
            sb.append("              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>\n");
            sb.append("              <wpml:useGlobalPayloadLensIndex>1</wpml:useGlobalPayloadLensIndex>\n");
            sb.append("            </wpml:actionActuatorFuncParam>\n");
            sb.append("          </wpml:action>\n");
            sb.append("        </wpml:actionGroup>\n");
            sb.append("        <wpml:waypointGimbalHeadingParam>\n");
            sb.append("          <wpml:waypointGimbalPitchAngle>0</wpml:waypointGimbalPitchAngle>\n");
            sb.append("          <wpml:waypointGimbalYawAngle>0</wpml:waypointGimbalYawAngle>\n");
            sb.append("        </wpml:waypointGimbalHeadingParam>\n");
            sb.append("        <wpml:isRisky>0</wpml:isRisky>\n");
            sb.append("        <wpml:waypointWorkType>0</wpml:waypointWorkType>\n");
            sb.append("      </Placemark>\n");
        }

        sb.append("    </Folder>\n");
        sb.append("  </Document>\n");
        sb.append("</kml>\n");
        return sb.toString();
    }

    public static String generateDocKml(String missionName, List<Waypoint> waypoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
        sb.append("  <Document>\n");
        sb.append("    <name>").append(escapeXml(missionName)).append("</name>\n");
        sb.append("    <open>1</open>\n");
        sb.append("    <description>Misión dronmxE compatible con Google Earth y DJI Fly RC 2</description>\n");
        sb.append("    <Style id=\"flightPathLine\">\n");
        sb.append("      <LineStyle>\n");
        sb.append("        <color>ff00ffff</color>\n"); // Amarillo/cian en formato KML AABBGGRR
        sb.append("        <width>4</width>\n");
        sb.append("      </LineStyle>\n");
        sb.append("      <PolyStyle>\n");
        sb.append("        <color>4400ffff</color>\n");
        sb.append("      </PolyStyle>\n");
        sb.append("    </Style>\n");
        sb.append("    <Style id=\"wpIcon\">\n");
        sb.append("      <IconStyle>\n");
        sb.append("        <scale>1.1</scale>\n");
        sb.append("        <Icon>\n");
        sb.append("          <href>https://maps.google.com/mapfiles/kml/paddle/red-circle.png</href>\n");
        sb.append("        </Icon>\n");
        sb.append("      </IconStyle>\n");
        sb.append("    </Style>\n");

        // Línea de la trayectoria de vuelo (Flight Path)
        sb.append("    <Placemark>\n");
        sb.append("      <name>Trayectoria de Vuelo</name>\n");
        sb.append("      <styleUrl>#flightPathLine</styleUrl>\n");
        sb.append("      <LineString>\n");
        sb.append("        <extrude>1</extrude>\n");
        sb.append("        <tessellate>1</tessellate>\n");
        sb.append("        <altitudeMode>relativeToGround</altitudeMode>\n");
        sb.append("        <coordinates>\n");
        for (Waypoint wp : waypoints) {
            sb.append(String.format(Locale.US, "          %.7f,%.7f,%.1f\n", wp.lon, wp.lat, wp.alt));
        }
        sb.append("        </coordinates>\n");
        sb.append("      </LineString>\n");
        sb.append("    </Placemark>\n");

        // Carpeta con Waypoints individuales
        sb.append("    <Folder>\n");
        sb.append("      <name>Waypoints de Misión (").append(waypoints.size()).append(")</name>\n");
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            sb.append("      <Placemark>\n");
            sb.append("        <name>WP ").append(i + 1).append("</name>\n");
            sb.append("        <description>Altitud: ").append(String.format(Locale.US, "%.1f", wp.alt)).append(" m | Velocidad: ").append(String.format(Locale.US, "%.1f", wp.speed)).append(" m/s</description>\n");
            sb.append("        <styleUrl>#wpIcon</styleUrl>\n");
            sb.append("        <Point>\n");
            sb.append("          <altitudeMode>relativeToGround</altitudeMode>\n");
            sb.append("          <coordinates>").append(String.format(Locale.US, "%.7f,%.7f,%.1f", wp.lon, wp.lat, wp.alt)).append("</coordinates>\n");
            sb.append("        </Point>\n");
            sb.append("      </Placemark>\n");
        }
        sb.append("    </Folder>\n");
        sb.append("  </Document>\n");
        sb.append("</kml>\n");
        return sb.toString();
    }
}
