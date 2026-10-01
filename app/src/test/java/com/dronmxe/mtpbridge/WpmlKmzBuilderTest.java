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

        // 2. Crear un KMZ de DJI ya válido para probar que no se re-empaquete innecesariamente
        sampleDjiKmzFile = tempFolder.newFile("mision_existente.kmz");
        try (FileOutputStream fos = new FileOutputStream(sampleDjiKmzFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            ZipEntry ze = new ZipEntry("wpmz/template.kml");
            zos.putNextEntry(ze);
            zos.write("<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\"></kml>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    @Test
    public void testEnsureDjiWpmlKmz_ConvertsRawKmlToValidDjiPackage() throws Exception {
        File outputDir = tempFolder.newFolder("output");
        File resultKmz = WpmlKmzBuilder.ensureDjiWpmlKmz(sampleKmlFile, outputDir);

        assertNotNull(resultKmz);
        assertTrue(resultKmz.exists());
        assertTrue(resultKmz.getName().endsWith(".kmz"));

        boolean hasTemplate = false;
        boolean hasWaylines = false;
        String templateXml = "";
        String waylinesXml = "";

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(resultKmz))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("wpmz/template.kml".equals(entry.getName())) {
                    hasTemplate = true;
                    templateXml = readEntryString(zis);
                } else if ("wpmz/waylines.wpml".equals(entry.getName())) {
                    hasWaylines = true;
                    waylinesXml = readEntryString(zis);
                }
            }
        }

        assertTrue("El KMZ generado debe contener wpmz/template.kml", hasTemplate);
        assertTrue("El KMZ generado debe contener wpmz/waylines.wpml", hasWaylines);

        // Validar namespace y estructura WPML
        assertTrue("template.kml debe incluir xmlns:wpml de DJI", templateXml.contains("xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\""));
        assertTrue("template.kml debe definir executeHeight", templateXml.contains("<wpml:executeHeight>50.0</wpml:executeHeight>"));
        assertTrue("template.kml debe contener las coordenadas de origen", templateXml.contains("-99.133208,19.432608,50.0"));

        assertTrue("waylines.wpml debe incluir xmlns:wpml de DJI", waylinesXml.contains("xmlns:wpml=\"http://www.dji.com/wpmz/1.0.3\""));
        assertTrue("waylines.wpml debe contener la etiqueta <wpml:waypoint>", waylinesXml.contains("<wpml:waypoint>"));
    }

    @Test
    public void testEnsureDjiWpmlKmz_PassesThroughAlreadyValidKmz() throws Exception {
        File outputDir = tempFolder.newFolder("output2");
        File result = WpmlKmzBuilder.ensureDjiWpmlKmz(sampleDjiKmzFile, outputDir);

        assertEquals("Debe retornar el mismo archivo sin duplicar", sampleDjiKmzFile.getAbsolutePath(), result.getAbsolutePath());
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
