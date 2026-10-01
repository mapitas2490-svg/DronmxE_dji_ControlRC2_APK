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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Motor Generador y Convertidor de Paquetes KMZ WPML Estándar para DJI Fly (RC 2 / RC Pro).
 * Convierte cualquier KML / KMZ de Google Earth, Litchi, QGIS o custom
 * en un paquete 100% compatible con DJI Fly conteniendo wpmz/template.kml y wpmz/waylines.wpml.
 */
public class WpmlKmzBuilder {

    private static final String TAG = "WpmlKmzBuilder";

    public static class Waypoint {
        public double lon;
        public double lat;
        public double alt = 50.0;
        public double speed = 5.0;

        public Waypoint(double lon, double lat, double alt) {
            this.lon = lon;
            this.lat = lat;
            this.alt = alt > 0 ? alt : 50.0;
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

    /**
     * Procesa un archivo KMZ/KML local y devuelve un archivo KMZ universal compatible con:
     * 1. Google Earth (doc.kml en la raíz)
     * 2. DJI Fly RC 2 / RC Pro (wpmz/template.kml y wpmz/waylines.wpml)
     */
    public static File ensureDjiWpmlKmz(File inputFile, File outputDir) {
        if (inputFile == null || !inputFile.exists()) return inputFile;

        try {
            // 1. Verificar si ya es un paquete universal (contiene wpmz/template.kml Y doc.kml)
            if (isAlreadyUniversal(inputFile)) {
                logD(TAG, "El archivo ya es un paquete universal válido (DJI Fly + Google Earth): " + inputFile.getName());
                return inputFile;
            }

            logD(TAG, "Misión no contiene formato universal. Generando KMZ compatible con Google Earth y DJI Fly...");

            // 2. Extraer waypoints del KML/KMZ de origen
            List<Waypoint> waypoints = extractWaypoints(inputFile);
            if (waypoints.isEmpty()) {
                logW(TAG, "No se encontraron waypoints en el archivo original. Devolviendo sin modificar.");
                return inputFile;
            }

            String missionName = inputFile.getName().replace(".kmz", "").replace(".kml", "");
            File targetKmz = new File(outputDir, missionName + "_dji_fly.kmz");

            // 3. Generar XML para Google Earth (doc.kml) y para DJI Fly (template.kml + waylines.wpml)
            String docKmlXml = generateDocKml(missionName, waypoints);
            String templateXml = generateTemplateKml(missionName, waypoints);
            String waylinesXml = generateWaylinesWpml(missionName, waypoints);

            // 4. Empaquetar todo en el archivo .kmz
            try (FileOutputStream fos = new FileOutputStream(targetKmz);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // Entry 1: doc.kml (ESTÁNDAR OGC PARA GOOGLE EARTH DESKTOP / WEB / MOBILE)
                ZipEntry entryDocKml = new ZipEntry("doc.kml");
                zos.putNextEntry(entryDocKml);
                zos.write(docKmlXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                // Entry 2: wpmz/template.kml (ESTÁNDAR DJI FLY WPML 1.0.3 PARA RC 2)
                ZipEntry entryTemplate = new ZipEntry("wpmz/template.kml");
                zos.putNextEntry(entryTemplate);
                zos.write(templateXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                // Entry 3: wpmz/waylines.wpml (ESTÁNDAR EJECUCIÓN WAYLINES DJI FLY)
                ZipEntry entryWaylines = new ZipEntry("wpmz/waylines.wpml");
                zos.putNextEntry(entryWaylines);
                zos.write(waylinesXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.finish();
            }

            logD(TAG, "✅ Paquete Universal KMZ generado: " + targetKmz.getAbsolutePath() + " (" + waypoints.size() + " WP)");
            return targetKmz;

        } catch (Exception e) {
            logE(TAG, "Error convirtiendo a paquete Universal KMZ: " + e.getMessage(), e);
            return inputFile;
        }
    }

    private static boolean isAlreadyUniversal(File file) {
        if (!file.getName().toLowerCase().endsWith(".kmz")) return false;
        boolean hasTemplate = false;
        boolean hasDocKml = false;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.contains("wpmz/template.kml") || name.contains("wpmz/waylines.wpml")) {
                    hasTemplate = true;
                }
                if (name.equals("doc.kml") || name.endsWith("/doc.kml") || (name.endsWith(".kml") && !name.startsWith("wpmz/"))) {
                    hasDocKml = true;
                }
            }
        } catch (Exception ignored) {}
        return hasTemplate && hasDocKml;
    }

    private static List<Waypoint> extractWaypoints(File file) throws Exception {
        List<Waypoint> result = new ArrayList<>();
        String xmlContent = "";

        if (file.getName().toLowerCase().endsWith(".kmz")) {
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().toLowerCase().endsWith(".kml") || entry.getName().toLowerCase().endsWith(".wpml")) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = zis.read(buf)) != -1) baos.write(buf, 0, r);
                        xmlContent += "\n" + baos.toString("UTF-8");
                    }
                }
            }
        } else {
            try (FileInputStream fis = new FileInputStream(file);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = fis.read(buf)) != -1) baos.write(buf, 0, r);
                xmlContent = baos.toString("UTF-8");
            }
        }

        if (xmlContent.isEmpty()) return result;

        // Extraer coordenadas de las etiquetas <coordinates>
        Pattern coordPattern = Pattern.compile("<coordinates>([\\s\\S]*?)</coordinates>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = coordPattern.matcher(xmlContent);

        while (matcher.find()) {
            String rawCoords = matcher.group(1).trim();
            String[] tokens = rawCoords.split("\\s+");
            for (String t : tokens) {
                String[] parts = t.split(",");
                if (parts.length >= 2) {
                    try {
                        double lon = Double.parseDouble(parts[0].trim());
                        double lat = Double.parseDouble(parts[1].trim());
                        double alt = (parts.length >= 3) ? Double.parseDouble(parts[2].trim()) : 50.0;
                        if (!Double.isNaN(lon) && !Double.isNaN(lat) && (lon != 0.0 || lat != 0.0)) {
                            result.add(new Waypoint(lon, lat, alt));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return result;
    }

    private static String generateTemplateKml(String missionName, List<Waypoint> waypoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\">\n");
        sb.append("  <Document>\n");
        sb.append("    <wpml:author>dronmxE</wpml:author>\n");
        sb.append("    <wpml:createTime>").append(System.currentTimeMillis()).append("</wpml:createTime>\n");
        sb.append("    <wpml:updateTime>").append(System.currentTimeMillis()).append("</wpml:updateTime>\n");
        sb.append("    <wpml:missionConfig>\n");
        sb.append("      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>\n");
        sb.append("      <wpml:finishAction>goHome</wpml:finishAction>\n");
        sb.append("      <wpml:exitOnRCLost>executeWayline</wpml:exitOnRCLost>\n");
        sb.append("      <wpml:takeOffSecurityHeight>20</wpml:takeOffSecurityHeight>\n");
        sb.append("      <wpml:globalTransitionalSpeed>10</wpml:globalTransitionalSpeed>\n");
        sb.append("      <wpml:droneInfo>\n");
        sb.append("        <wpml:droneEnumValue>68</wpml:droneEnumValue>\n");
        sb.append("        <wpml:droneSubEnumValue>0</wpml:droneSubEnumValue>\n");
        sb.append("      </wpml:droneInfo>\n");
        sb.append("    </wpml:missionConfig>\n");
        sb.append("    <Folder>\n");
        sb.append("      <wpml:templateType>waypoint</wpml:templateType>\n");
        sb.append("      <wpml:templateId>0</wpml:templateId>\n");
        sb.append("      <wpml:waylineCoordinateSysDef>\n");
        sb.append("        <wpml:coordinateSysEquip>GPS</wpml:coordinateSysEquip>\n");
        sb.append("        <wpml:heightMode>relativeToStartPoint</wpml:heightMode>\n");
        sb.append("      </wpml:waylineCoordinateSysDef>\n");
        sb.append("      <wpml:autoFlightSpeed>5</wpml:autoFlightSpeed>\n");

        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            sb.append("      <Placemark>\n");
            sb.append("        <Point>\n");
            sb.append("          <coordinates>").append(wp.lon).append(",").append(wp.lat).append(",").append(wp.alt).append("</coordinates>\n");
            sb.append("        </Point>\n");
            sb.append("        <wpml:index>").append(i).append("</wpml:index>\n");
            sb.append("        <wpml:executeHeight>").append(wp.alt).append("</wpml:executeHeight>\n");
            sb.append("        <wpml:waypointSpeed>").append(wp.speed).append("</wpml:waypointSpeed>\n");
            sb.append("        <wpml:waypointHeadingParam>\n");
            sb.append("          <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>\n");
            sb.append("        </wpml:waypointHeadingParam>\n");
            sb.append("      </Placemark>\n");
        }

        sb.append("    </Folder>\n");
        sb.append("  </Document>\n");
        sb.append("</kml>\n");
        return sb.toString();
    }

    private static String generateWaylinesWpml(String missionName, List<Waypoint> waypoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\">\n");
        sb.append("  <Document>\n");
        sb.append("    <wpml:missionConfig>\n");
        sb.append("      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>\n");
        sb.append("      <wpml:finishAction>goHome</wpml:finishAction>\n");
        sb.append("      <wpml:exitOnRCLost>executeWayline</wpml:exitOnRCLost>\n");
        sb.append("      <wpml:takeOffSecurityHeight>20</wpml:takeOffSecurityHeight>\n");
        sb.append("      <wpml:globalTransitionalSpeed>10</wpml:globalTransitionalSpeed>\n");
        sb.append("    </wpml:missionConfig>\n");
        sb.append("    <Folder>\n");
        sb.append("      <wpml:templateId>0</wpml:templateId>\n");
        sb.append("      <wpml:waylineId>0</wpml:waylineId>\n");
        sb.append("      <wpml:autoFlightSpeed>5</wpml:autoFlightSpeed>\n");

        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            sb.append("      <wpml:waypoint>\n");
            sb.append("        <Point>\n");
            sb.append("          <coordinates>").append(wp.lon).append(",").append(wp.lat).append(",").append(wp.alt).append("</coordinates>\n");
            sb.append("        </Point>\n");
            sb.append("        <wpml:index>").append(i).append("</wpml:index>\n");
            sb.append("        <wpml:executeHeight>").append(wp.alt).append("</wpml:executeHeight>\n");
            sb.append("        <wpml:waypointSpeed>").append(wp.speed).append("</wpml:waypointSpeed>\n");
            sb.append("      </wpml:waypoint>\n");
        }

        sb.append("    </Folder>\n");
        sb.append("  </Document>\n");
        sb.append("</kml>\n");
        return sb.toString();
    }

    private static String generateDocKml(String missionName, List<Waypoint> waypoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
        sb.append("  <Document>\n");
        sb.append("    <name>").append(escapeXml(missionName)).append("</name>\n");
        sb.append("    <open>1</open>\n");
        sb.append("    <description>Misión dronmxE compatible con Google Earth y DJI Fly RC 2</description>\n");
        sb.append("    <Style id=\"flightPathLine\">\n");
        sb.append("      <LineStyle>\n");
        sb.append("        <color>ff00ffff</color>\n"); // Amarillo en formato KML AABBGGRR
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
            sb.append("          ").append(wp.lon).append(",").append(wp.lat).append(",").append(wp.alt).append("\n");
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
            sb.append("        <description>Altitud: ").append(wp.alt).append(" m | Velocidad: ").append(wp.speed).append(" m/s</description>\n");
            sb.append("        <styleUrl>#wpIcon</styleUrl>\n");
            sb.append("        <Point>\n");
            sb.append("          <altitudeMode>relativeToGround</altitudeMode>\n");
            sb.append("          <coordinates>").append(wp.lon).append(",").append(wp.lat).append(",").append(wp.alt).append("</coordinates>\n");
            sb.append("        </Point>\n");
            sb.append("      </Placemark>\n");
        }
        sb.append("    </Folder>\n");
        sb.append("  </Document>\n");
        sb.append("</kml>\n");
        return sb.toString();
    }
}

