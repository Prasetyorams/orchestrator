package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paket yang diunggah manual: dibaca seperti robot membacanya, atau ditolak dengan alasan yang jelas. */
class PackageInspectorTest {

    static final String MAIN = """
            <Activity x:Class="Main" xmlns="http://schemas.microsoft.com/netfx/2009/xaml/activities"
                      xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
                      xmlns:mva="clr-namespace:Microsoft.VisualBasic.Activities;assembly=System.Activities"
                      xmlns:c="clr-namespace:CustomSystem;assembly=Custom.System">
              <Sequence DisplayName="Utama" />
            </Activity>""";

    static byte[] zip(String... nameThenContent) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                out.putNextEntry(new ZipEntry(nameThenContent[i]));
                out.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    static Map<String, String> unzip(byte[] content) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    /** Seperti ProjectPackage.Build di Studio (Terbitkan → Folder lokal). */
    static byte[] studioNupkg(String version) {
        return zip(
                "Record_Video.nuspec", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
                          <metadata>
                            <id>Record_Video</id>
                            <version>%s</version>
                            <description>Rekam layar &amp; unggah</description>
                            <tags>OpenStudio Automation entry:Main.xaml</tags>
                          </metadata>
                        </package>""".formatted(version),
                "lib/net45/Main.xaml", MAIN,
                "lib/net45/project.json", "{\"name\":\"Record Video\",\"main\":\"Main.xaml\","
                        + "\"dependencies\":{\"JakForge.Mail.Activities\":\"1.0.0\"}}",
                "lib/net45/Sub%20Folder/Login%20Form.xaml", MAIN,
                "_rels/.rels", "<Relationships/>",
                "package/services/metadata/core-properties/abc.psmdcp", "<coreProperties/>",
                "[Content_Types].xml", "<Types/>");
    }

    static ApiException rejected(byte[] file, String name) {
        return assertThrows(ApiException.class, () -> PackageInspector.inspect(file, name));
    }

    // -------------------------------------------------------------------- yang diterima

    @Test
    @DisplayName(".nupkg Studio: isi lib/net45/ dipindah ke akar, nama dari project.json, versi dari .nuspec")
    void studioNupkgRepacked() throws IOException {
        PackageInspector.Inspection p = PackageInspector.inspect(studioNupkg("1.2.3"), "Record_Video.1.2.3.nupkg");

        assertEquals("Record Video", p.name());
        assertEquals("1.2.3", p.version());
        assertEquals("Rekam layar & unggah", p.description());
        assertEquals("Main.xaml", p.entryPoint());
        assertEquals(PackageInspector.Layout.NUPKG, p.layout());
        assertTrue(p.converted());

        Map<String, String> files = unzip(p.content());
        assertEquals(List.of("Main.xaml", "project.json", "Sub Folder/Login Form.xaml"), new ArrayList<>(files.keySet()));
        assertEquals(MAIN, files.get("Main.xaml"));

        assertEquals(List.of("JakForge.Mail.Activities 1.0.0"), p.dependencies());
        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().getFirst().contains("JakForge.Mail.Activities 1.0.0"), p.warnings().toString());
    }

    @Test
    @DisplayName("zip proyek (hasil unduh Orchestrator): disimpan apa adanya, versi dari nama berkas")
    void projectZipStoredAsIs() {
        byte[] file = zip("Main.xaml", MAIN, "project.json", "{\"name\":\"Tagihan\",\"main\":\"Main.xaml\"}",
                "Framework/Init.xaml", MAIN);

        PackageInspector.Inspection p = PackageInspector.inspect(file, "Tagihan.1.0.3.zip");

        assertEquals("Tagihan", p.name());
        assertEquals("1.0.3", p.version());
        assertEquals(PackageInspector.Layout.ZIP, p.layout());
        assertFalse(p.converted());
        assertSame(file, p.content());
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
    }

    @Test
    @DisplayName("zip berisi satu folder proyek: isinya dipindah ke akar")
    void zippedFolderUnwrapped() throws IOException {
        byte[] file = zip("Tagihan/Main.xaml", MAIN, "Tagihan/project.json", "{\"name\":\"Tagihan\"}",
                "Tagihan/Data/config.xlsx", "x");

        PackageInspector.Inspection p = PackageInspector.inspect(file, "Tagihan.2.0.0.zip");

        assertEquals(PackageInspector.Layout.ZIP_IN_FOLDER, p.layout());
        assertEquals(List.of("Data/config.xlsx", "Main.xaml", "project.json"), new ArrayList<>(unzip(p.content()).keySet()));
    }

    @Test
    @DisplayName("titik masuk: main di project.json (juga di subfolder), lalu Main.xaml, lalu satu-satunya .xaml")
    void entryPointOrder() {
        assertEquals("Flows/Start.xaml", PackageInspector.inspect(zip("Main.xaml", MAIN, "Flows/Start.xaml", MAIN,
                "project.json", "{\"name\":\"A\",\"main\":\"Flows\\\\Start.xaml\"}"), "A.1.0.0.zip").entryPoint());
        assertEquals("MAIN.xaml", PackageInspector.inspect(zip("MAIN.xaml", MAIN, "Lain.xaml", MAIN), "A.1.0.0.zip").entryPoint());
        assertEquals("Robot.xaml", PackageInspector.inspect(zip("Robot.xaml", MAIN), "A.1.0.0.zip").entryPoint());

        PackageInspector.Inspection missing = PackageInspector.inspect(zip("Main.xaml", MAIN,
                "project.json", "{\"main\":\"Hilang.xaml\"}"), "A.1.0.0.zip");
        assertEquals("Main.xaml", missing.entryPoint());
        assertTrue(missing.warnings().getFirst().contains("'Hilang.xaml' yang tidak ada"), missing.warnings().toString());
    }

    @Test
    @DisplayName("nama dan versi: nama berkas bertitik dan pra-rilis; project.json menang atas nama berkas")
    void nameAndVersionFromFileName() {
        PackageInspector.Inspection p = PackageInspector.inspect(zip("Main.xaml", MAIN), "My.Project.2.1.0-beta.zip");
        assertEquals("My.Project", p.name());
        assertEquals("2.1.0-beta", p.version());

        assertEquals("Tagihan Bulanan", PackageInspector.inspect(zip("Main.xaml", MAIN, "project.json",
                "{\"name\":\"Tagihan Bulanan\"}"), "tagihan.1.0.0.zip").name());
    }

    @Test
    @DisplayName("pustaka yang bukan bawaan OpenStudio: diterima dengan peringatan, bukan ditolak")
    void unknownAssemblyWarns() {
        String xaml = MAIN.replace("xmlns:c=", "xmlns:v=\"clr-namespace:Vendor;assembly=Vendor.Activities\" xmlns:c=");

        PackageInspector.Inspection p = PackageInspector.inspect(zip("Main.xaml", xaml), "A.1.0.0.zip");

        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().getFirst().contains("Vendor.Activities"), p.warnings().toString());
    }

    // -------------------------------------------------------------------- yang ditolak

    @Test
    @DisplayName("bukan .nupkg/.zip, atau bukan arsip: ditolak dengan kode masing-masing")
    void notAPackage() {
        assertEquals(PackageInspector.FILE_TYPE, rejected(zip("Main.xaml", MAIN), "Main.xaml").errorCode());
        assertEquals(PackageInspector.CORRUPT, rejected("bukan zip".getBytes(), "A.1.0.0.zip").errorCode());

        byte[] truncated = java.util.Arrays.copyOf(studioNupkg("1.0.0"), 60);
        assertEquals(PackageInspector.CORRUPT, rejected(truncated, "A.1.0.0.nupkg").errorCode());
    }

    @Test
    @DisplayName("paket activity (.dll tanpa workflow): 'pasang lewat Kelola Paket'")
    void activityPackageRejected() {
        byte[] file = zip("JakForge.Mail.Activities.nuspec", "<package><metadata><id>JakForge.Mail.Activities</id>"
                + "<version>1.0.0</version></metadata></package>", "lib/net462/JakForge.Mail.Activities.dll", "MZ");

        ApiException e = rejected(file, "JakForge.Mail.Activities.1.0.0.nupkg");

        assertEquals(PackageInspector.NOT_PROCESS, e.errorCode());
        assertTrue(e.getMessage().contains("Kelola Paket"), e.getMessage());
    }

    @Test
    @DisplayName("paket UiPath Studio: ditolak, dengan buktinya")
    void uipathRejected() {
        String xaml = MAIN.replace("xmlns:c=", "xmlns:ui=\"http://schemas.uipath.com/workflow/activities\" xmlns:c=");

        ApiException byNamespace = rejected(zip("Main.xaml", xaml), "A.1.0.0.zip");
        assertEquals(PackageInspector.UIPATH, byNamespace.errorCode());
        assertTrue(byNamespace.getMessage().contains("namespace UiPath di Main.xaml"), byNamespace.getMessage());

        ApiException byDependency = rejected(zip("Main.xaml", MAIN, "project.json",
                "{\"dependencies\":{\"UiPath.System.Activities\":\"[23.10.3]\"}}"), "A.1.0.0.zip");
        assertTrue(byDependency.getMessage().contains("dependency UiPath.System.Activities"), byDependency.getMessage());

        String assembly = MAIN.replace("assembly=Custom.System", "assembly=UiPath.System.Activities");
        assertEquals(PackageInspector.UIPATH, rejected(zip("Main.xaml", assembly), "A.1.0.0.zip").errorCode());
    }

    @Test
    @DisplayName("jalur yang keluar folder, mutlak, ber-escape OPC, atau tidak sah di Windows: ditolak")
    void unsafePaths() {
        assertEquals(PackageInspector.UNSAFE_PATH, rejected(zip("Main.xaml", MAIN, "../jahat.xaml", MAIN), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.UNSAFE_PATH, rejected(zip("Main.xaml", MAIN, "/etc/x", "x"), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.UNSAFE_PATH, rejected(zip("Main.xaml", MAIN, "C:/Windows/x", "x"), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.UNSAFE_PATH, rejected(zip("Main.xaml", MAIN, "a?b.txt", "x"), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.UNSAFE_PATH, rejected(zip("A.nuspec", "<package/>", "lib/net45/Main.xaml", MAIN,
                "lib/net45/%2E%2E/jahat.xaml", MAIN), "A.1.0.0.nupkg").errorCode());
    }

    @Test
    @DisplayName("berkas yang hanya berbeda huruf besar-kecil: ditolak (saling menimpa di Windows)")
    void caseInsensitiveDuplicates() {
        assertEquals(PackageInspector.CORRUPT, rejected(zip("Main.xaml", MAIN, "main.XAML", MAIN), "A.1.0.0.zip").errorCode());
    }

    @Test
    @DisplayName("XAML: DOCTYPE/entitas luar, XML rusak, dan titik masuk yang bukan <Activity> ditolak")
    void invalidXaml() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + MAIN;
        assertEquals(PackageInspector.INVALID_XAML, rejected(zip("Main.xaml", xxe), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.INVALID_XAML, rejected(zip("Main.xaml", "<Activity"), "A.1.0.0.zip").errorCode());

        ApiException notWorkflow = rejected(zip("Main.xaml", "<ResourceDictionary xmlns=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\"/>"),
                "A.1.0.0.zip");
        assertEquals(PackageInspector.INVALID_XAML, notWorkflow.errorCode());
        assertTrue(notWorkflow.getMessage().contains("bukan <Activity>"), notWorkflow.getMessage());
    }

    @Test
    @DisplayName("tanpa titik masuk yang jelas, tanpa workflow, project.json rusak, tanpa versi")
    void structureProblems() {
        assertEquals(PackageInspector.ENTRY_POINT, rejected(zip("Satu.xaml", MAIN, "Dua.xaml", MAIN), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.NO_WORKFLOW, rejected(zip("readme.txt", "x"), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.NO_WORKFLOW, rejected(zip("a/Main.xaml", MAIN, "b/Main.xaml", MAIN), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.INVALID_PROJECT, rejected(zip("Main.xaml", MAIN, "project.json", "{rusak"), "A.1.0.0.zip").errorCode());

        ApiException noVersion = rejected(zip("Main.xaml", MAIN), "Tagihan.zip");
        assertEquals(PackageNaming.INVALID_VERSION, noVersion.errorCode());
        assertTrue(noVersion.getMessage().contains("Tagihan.1.0.3.zip"), noVersion.getMessage());
    }

    @Test
    @DisplayName("versi di .nuspec harus sah menurut aturan yang sama dengan Studio")
    void nuspecVersionValidated() {
        assertEquals(PackageNaming.INVALID_VERSION, rejected(studioNupkg("terbaru"), "Record_Video.nupkg").errorCode());
    }

    @Test
    @DisplayName("isi yang dibaca ulang dari hasil konversi sama dengan aslinya, bit demi bit")
    void repackedBytesIdentical() throws IOException {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) i;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("A.nuspec"));
            out.write("<package><metadata><id>A</id><version>1.0.0</version></metadata></package>".getBytes());
            out.putNextEntry(new ZipEntry("lib/net45/Main.xaml"));
            out.write(MAIN.getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry("lib/net45/Data/bin.dat"));
            out.write(binary);
        }

        PackageInspector.Inspection p = PackageInspector.inspect(bytes.toByteArray(), "A.1.0.0.nupkg");

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(p.content()))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().equals("Data/bin.dat")) assertArrayEquals(binary, in.readAllBytes());
            }
        }
    }

    @Test
    @DisplayName("percent-decoding OPC: %20 menjadi spasi, '+' tetap '+', UTF-8 utuh")
    void unescapePart() {
        assertEquals("Sub Folder/Login Form.xaml", PackageInspector.unescapePart("Sub%20Folder/Login%20Form.xaml"));
        assertEquals("a+b.xaml", PackageInspector.unescapePart("a+b.xaml"));
        assertEquals("Café.xaml", PackageInspector.unescapePart("Caf%C3%A9.xaml"));
        assertEquals("100%.txt", PackageInspector.unescapePart("100%.txt"));
    }
}
