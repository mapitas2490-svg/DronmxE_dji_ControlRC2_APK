package com.dronmxe.mtpbridge;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class WpmlKmzBuilderTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File sampleKmlFile;
    private File sampleDjiKmzFile;

    @Before
    public void setUp() throws Exception {
        // 1. Crear un KML plano de ejemplo (Google Earth / QGIS)
        sampleKmlFile = tempFolder.newFile("mision_test.kml");
        String kmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n" +
                "  <Document>\n" +
                "    <name>Mision Test Google Earth</name>\n" +
                "    <Placemark>\n" +
                "      <name>Ruta 1</name>\n" +
                "      <LineString>\n" +
                "        <coordinates>\n" +
                "          -99.133208,19.432608,50.0\n" +
                "          -99.134000,19.433000,60.5\n" +
                "          -99.135000,19.434000,75.2\n" +
                "        </coordinates>\n" +
                "      </LineString>\n" +
                "    </Placemark>\n" +
                "  </Document>\n" +
                "</kml>";
        try (FileOutputStream fos = new FileOutputStream(sampleKmlFile)) {
            fos.write(kmlContent.getBytes(StandardCharsets.UTF_8));
        }

        // 2. Crear un KMZ ya universal (contiene doc.kml, wpmz/template.kml y wpmz/waylines.wpml válido)
        sampleDjiKmzFile = tempFolder.newFile("mision_existente.kmz");
        try (FileOutputStream fos = new FileOutputStream(sampleDjiKmzFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            ZipEntry zeDoc = new ZipEntry("doc.kml");
            zos.putNextEntry(zeDoc);
            zos.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\"></kml>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry zeTpl = new ZipEntry("wpmz/template.kml");
            zos.putNextEntry(zeTpl);
            zos.write("<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\"><Document><wpml:missionConfig></wpml:missionConfig></Document></kml>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry zeWpml = new ZipEntry("wpmz/waylines.wpml");
            zos.putNextEntry(zeWpml);
            zos.write("<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\"><Document><Folder><Placemark><Point><coordinates>-99.1,19.4</coordinates></Point><wpml:executeHeight>50.0</wpml:executeHeight></Placemark></Folder></Document></kml>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    @Test
    public void testEnsureDjiWpmlKmz_ConvertsRawKmlToValidUniversalPackage() throws Exception {
        File outputDir = tempFolder.newFolder("output");
        File resultKmz = WpmlKmzBuilder.ensureDjiWpmlKmz(sampleKmlFile, outputDir);

        assertNotNull(resultKmz);
        assertTrue(resultKmz.exists());
        assertTrue(resultKmz.getName().endsWith(".kmz"));

        boolean hasDocKml = false;
        boolean hasTemplate = false;
        boolean hasWaylines = false;
        String docKmlXml = "";
        String templateXml = "";
        String waylinesXml = "";

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(resultKmz))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("doc.kml".equals(entry.getName())) {
                    hasDocKml = true;
                    docKmlXml = readEntryString(zis);
                } else if ("wpmz/template.kml".equals(entry.getName())) {
                    hasTemplate = true;
                    templateXml = readEntryString(zis);
                } else if ("wpmz/waylines.wpml".equals(entry.getName())) {
                    hasWaylines = true;
                    waylinesXml = readEntryString(zis);
                }
            }
        }

        // 1. Validar compatibilidad con Google Earth (doc.kml en raíz)
        assertTrue("El KMZ generado debe contener doc.kml para Google Earth", hasDocKml);
        assertTrue("doc.kml debe contener Document y LineString para visualizar en 3D", docKmlXml.contains("<LineString>"));
        assertTrue("doc.kml debe contener coordenadas", docKmlXml.contains("-99.1332080,19.4326080,50.0"));
        assertTrue("doc.kml debe listar los waypoints con sus altitudes", docKmlXml.contains("WP 1"));

        // 2. Validar compatibilidad con DJI Fly / RC 2 (wpmz/template.kml y wpmz/waylines.wpml con ejecución completa)
        assertTrue("El KMZ generado debe contener wpmz/template.kml", hasTemplate);
        assertTrue("El KMZ generado debe contener wpmz/waylines.wpml", hasWaylines);
        assertTrue("template.kml debe incluir xmlns:wpml de DJI", templateXml.contains("xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\""));
        assertTrue("template.kml debe incluir missionConfig", templateXml.contains("<wpml:missionConfig>"));
        assertTrue("waylines.wpml debe incluir xmlns:wpml de DJI", waylinesXml.contains("xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\""));
        assertTrue("waylines.wpml debe contener Placemarks para ejecución en DJI Fly", waylinesXml.contains("<Placemark>"));
        assertTrue("waylines.wpml debe definir executeHeight", waylinesXml.contains("<wpml:executeHeight>50.00</wpml:executeHeight>"));
        assertTrue("waylines.wpml debe definir waypointSpeed", waylinesXml.contains("<wpml:waypointSpeed>8.0</wpml:waypointSpeed>"));
        assertTrue("waylines.wpml debe contener actionGroup para cámara y gimbal", waylinesXml.contains("<wpml:actionGroup>"));
        assertTrue("waylines.wpml debe contener takePhoto y gimbalRotate", waylinesXml.contains("takePhoto") && waylinesXml.contains("gimbalRotate"));
    }

    @Test
    public void testEnsureDjiWpmlKmz_PassesThroughAlreadyValidKmz() throws Exception {
        File outputDir = tempFolder.newFolder("output2");
        File result = WpmlKmzBuilder.ensureDjiWpmlKmz(sampleDjiKmzFile, outputDir);

        assertEquals("Debe retornar el mismo archivo sin duplicar", sampleDjiKmzFile.getAbsolutePath(), result.getAbsolutePath());
    }

    @Test
    public void testEnsureDjiWpmlKmz_ConvertsRealDeviceKmzToGoogleEarthCompatible() throws Exception {
        File realDeviceKmz = new File("C:\\Users\\edgar\\.gemini\\antigravity-ide\\scratch\\test_harness\\Mision_10-01_01_orig.kmz");
        if (!realDeviceKmz.exists()) return;

        File outputDir = tempFolder.newFolder("output_real");
        File resultKmz = WpmlKmzBuilder.ensureDjiWpmlKmz(realDeviceKmz, outputDir);

        assertNotNull(resultKmz);
        assertTrue(resultKmz.exists());

        boolean hasDocKml = false;
        boolean hasTemplate = false;
        boolean hasWaylines = false;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(resultKmz))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("doc.kml".equals(entry.getName())) hasDocKml = true;
                if ("wpmz/template.kml".equals(entry.getName())) hasTemplate = true;
                if ("wpmz/waylines.wpml".equals(entry.getName())) hasWaylines = true;
            }
        }

        assertTrue("Debe generar doc.kml para Google Earth a partir de la mision real del celular", hasDocKml);
        assertTrue("Debe mantener wpmz/template.kml para DJI Fly", hasTemplate);
        assertTrue("Debe mantener wpmz/waylines.wpml para DJI Fly", hasWaylines);
    }

    private String readEntryString(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = zis.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toString("UTF-8");
    }
}
