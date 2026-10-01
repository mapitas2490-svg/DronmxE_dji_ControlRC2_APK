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

    /**
     * Procesa un archivo KMZ/KML local y devuelve un archivo KMZ formateado para DJI Fly.
     */
    public static File ensureDjiWpmlKmz(File inputFile, File outputDir) {
        if (inputFile == null || !inputFile.exists()) return inputFile;

        try {
            // 1. Verificar si ya es un paquete WPML de DJI (contiene wpmz/template.kml)
            if (isAlreadyDjiWpml(inputFile)) {
                Log.d(TAG, "El archivo ya es un paquete DJI WPML válido: " + inputFile.getName());
                return inputFile;
            }

            Log.d(TAG, "Misión no contiene formato WPML DJI. Convirtiendo a KMZ estándar para DJI Fly...");

            // 2. Extraer waypoints del KML/KMZ de origen
            List<Waypoint> waypoints = extractWaypoints(inputFile);
            if (waypoints.isEmpty()) {
                Log.w(TAG, "No se encontraron waypoints en el archivo original. Devolviendo sin modificar.");
                return inputFile;
            }

            String missionName = inputFile.getName().replace(".kmz", "").replace(".kml", "");
            File targetKmz = new File(outputDir, missionName + "_dji_fly.kmz");

            // 3. Generar el XML template.kml y waylines.wpml de DJI
            String templateXml = generateTemplateKml(missionName, waypoints);
            String waylinesXml = generateWaylinesWpml(missionName, waypoints);

            // 4. Empaquetar ambos en wpmz/template.kml y wpmz/waylines.wpml dentro del .kmz
            try (FileOutputStream fos = new FileOutputStream(targetKmz);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // Entry 1: wpmz/template.kml
                ZipEntry entryTemplate = new ZipEntry("wpmz/template.kml");
                zos.putNextEntry(entryTemplate);
                zos.write(templateXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                // Entry 2: wpmz/waylines.wpml
                ZipEntry entryWaylines = new ZipEntry("wpmz/waylines.wpml");
                zos.putNextEntry(entryWaylines);
                zos.write(waylinesXml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.finish();
            }

            Log.d(TAG, "✅ Paquete DJI WPML KMZ generado con éxito: " + targetKmz.getAbsolutePath() + " (" + waypoints.size() + " WP)");
            return targetKmz;

        } catch (Exception e) {
            Log.e(TAG, "Error convirtiendo a paquete DJI WPML KMZ: " + e.getMessage(), e);
            return inputFile;
        }
    }

    private static boolean isAlreadyDjiWpml(File file) {
        if (!file.getName().toLowerCase().endsWith(".kmz")) return false;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.contains("wpmz/template.kml") || name.contains("wpmz/waylines.wpml")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
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
}
