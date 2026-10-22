package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static id.jakforge.openorchestrator.service.PackageInspectorTest.MAIN;
import static id.jakforge.openorchestrator.service.PackageInspectorTest.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Library OpenStudio yang diunggah ke feed library (V20). */
class LibraryInspectorTest {

    /** Bita awal DLL .NET (berkas PE): cukup untuk pemeriksaan "MZ". */
    private static final String DLL = "MZ\u0090\u0000 rakitan uji";

    static String nuspec(String id, String version, String tags, String dependencies) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
                  <metadata>
                    <id>%s</id>
                    <version>%s</version>
                    <authors>Tim RPA</authors>
                    <description>Activity tagihan bersama</description>
                    <tags>%s</tags>
                    %s
                  </metadata>
                </package>""".formatted(id, version, tags, dependencies);
    }

    /** Bentuk hasil Terbitkan proyek Library di OpenStudio. */
    static byte[] library(String id, String version, String dependencies) {
        return zip(id + ".nuspec", nuspec(id, version, "OpenStudio Library", dependencies),
                "lib/net45/" + id + ".dll", DLL,
                "[Content_Types].xml", "<Types/>",
                "_rels/.rels", "<Relationships/>");
    }

    private static ApiException rejected(byte[] file, String name) {
        return assertThrows(ApiException.class, () -> LibraryInspector.inspect(file, name));
    }

    // -------------------------------------------------------------------- diterima

    @Test
    @DisplayName("library OpenStudio: Id, versi, keterangan, penulis, DLL, dependensi dari .nuspec (grup dan tanpa grup)")
    void studioLibrary() {
        LibraryInspector.Inspection l = LibraryInspector.inspect(library("Tagihan.Umum", "1.2.0", """
                <dependencies>
                  <group targetFramework=".NETFramework4.5">
                    <dependency id="Tagihan.Dasar" version="1.0.0" />
                    <dependency id="OpenStudio.Mail.Activities" version="[2.0.1]" />
                  </group>
                  <group targetFramework=".NETFramework4.6.2">
                    <dependency id="tagihan.dasar" version="9.9.9" />
                  </group>
                  <dependency id="Tanpa.Grup" version="3.0.0" />
                </dependencies>"""), "Tagihan.Umum.1.2.0.nupkg");

        assertEquals("Tagihan.Umum", l.name());
        assertEquals("1.2.0", l.version());
        assertEquals("Activity tagihan bersama", l.description());
        assertEquals("Tim RPA", l.authors());
        assertEquals("lib/net45/Tagihan.Umum.dll", l.assemblyFile());
        assertEquals(Map.of("Tagihan.Dasar", "1.0.0", "OpenStudio.Mail.Activities", "[2.0.1]", "Tanpa.Grup", "3.0.0"),
                l.dependencies(), "grup pertama yang menang untuk Id yang sama");
        assertTrue(l.warnings().isEmpty(), l.warnings().toString());
    }

    @Test
    @DisplayName("tag dipisah spasi, huruf apa pun: 'openstudio library' dan tag tambahan tetap library")
    void tagWords() {
        assertTrue(LibraryInspector.hasLibraryTag("OpenStudio Library"));
        assertTrue(LibraryInspector.hasLibraryTag("library  openstudio tagihan"));
        assertFalse(LibraryInspector.hasLibraryTag("OpenStudio Automation entry:Main.xaml"));
        assertFalse(LibraryInspector.hasLibraryTag("OpenStudioLibrary"));
        assertFalse(LibraryInspector.hasLibraryTag(null));
    }

    @Test
    @DisplayName("nama DLL lain dari Id: diterima dengan peringatan")
    void assemblyNameDiffers() {
        byte[] file = zip("A.nuspec", nuspec("Tagihan.Umum", "1.0.0", "OpenStudio Library", ""),
                "lib/net45/TagihanUmum.dll", DLL);

        LibraryInspector.Inspection l = LibraryInspector.inspect(file, "x.nupkg");

        assertEquals("lib/net45/TagihanUmum.dll", l.assemblyFile());
        assertEquals(1, l.warnings().size());
        assertTrue(l.warnings().getFirst().contains("Tagihan.Umum.dll"), l.warnings().toString());
    }

    @Test
    @DisplayName("nama bagian OPC yang di-escape (%20) dibaca seperti aslinya")
    void escapedPartNames() {
        byte[] file = zip("A.nuspec", nuspec("Tagihan.Umum", "1.0.0", "OpenStudio Library", ""),
                "lib/net45/Tagihan%20Umum.dll", DLL);

        assertEquals("lib/net45/Tagihan Umum.dll", LibraryInspector.inspect(file, "x.nupkg").assemblyFile());
    }

    // -------------------------------------------------------------------- ditolak

    @Test
    @DisplayName("bukan .nupkg, atau bukan arsip: ditolak")
    void notANupkg() {
        assertEquals(LibraryInspector.FILE_TYPE, rejected(library("A", "1.0.0", ""), "A.1.0.0.zip").errorCode());
        assertEquals(PackageInspector.CORRUPT, rejected("bukan zip".getBytes(), "A.1.0.0.nupkg").errorCode());
        assertEquals(LibraryInspector.NOT_LIBRARY,
                rejected(zip("lib/net45/A.dll", DLL), "A.1.0.0.nupkg").errorCode(), "tanpa .nuspec");
    }

    @Test
    @DisplayName("paket proses: diarahkan ke Unggah Paket")
    void processPackage() {
        ApiException e = rejected(PackageInspectorTest.studioNupkg("1.0.0"), "Record_Video.1.0.0.nupkg");

        assertEquals(LibraryInspector.NOT_LIBRARY, e.errorCode());
        assertTrue(e.getMessage().contains("Unggah Paket"), e.getMessage());
    }

    @Test
    @DisplayName("paket activity tanpa tag OpenStudio Library: diarahkan ke Kelola Paket")
    void activityPackage() {
        byte[] file = zip("JakForge.Mail.Activities.nuspec", nuspec("JakForge.Mail.Activities", "1.0.0", "OpenStudio Activities", ""),
                "lib/net462/JakForge.Mail.Activities.dll", DLL);

        ApiException e = rejected(file, "JakForge.Mail.Activities.1.0.0.nupkg");

        assertEquals(LibraryInspector.NOT_LIBRARY, e.errorCode());
        assertTrue(e.getMessage().contains("Kelola Paket"), e.getMessage());
    }

    @Test
    @DisplayName("bukan tepat satu lib/net45/*.dll: tanpa DLL, dua DLL, target lain, atau di subfolder")
    void exactlyOneAssembly() {
        String spec = nuspec("A", "1.0.0", "OpenStudio Library", "");

        assertEquals(LibraryInspector.ASSEMBLY, rejected(zip("A.nuspec", spec, "readme.txt", "x"), "a.nupkg").errorCode());
        assertEquals(LibraryInspector.ASSEMBLY,
                rejected(zip("A.nuspec", spec, "lib/net45/A.dll", DLL, "lib/net45/B.dll", DLL), "a.nupkg").errorCode());
        assertEquals(LibraryInspector.ASSEMBLY, rejected(zip("A.nuspec", spec, "lib/net462/A.dll", DLL), "a.nupkg").errorCode());
        assertEquals(LibraryInspector.ASSEMBLY,
                rejected(zip("A.nuspec", spec, "lib/net45/sub/A.dll", DLL), "a.nupkg").errorCode());
        assertEquals(LibraryInspector.ASSEMBLY,
                rejected(zip("A.nuspec", spec, "lib/net45/A.dll", DLL, "tools/x.dll", DLL), "a.nupkg").errorCode());
    }

    @Test
    @DisplayName("DLL yang bukan berkas PE: ditolak")
    void notAnAssembly() {
        byte[] file = zip("A.nuspec", nuspec("A", "1.0.0", "OpenStudio Library", ""), "lib/net45/A.dll", "bukan dll");

        assertEquals(LibraryInspector.ASSEMBLY, rejected(file, "a.nupkg").errorCode());
    }

    @Test
    @DisplayName("Id dan versi: wajib ada, Id hanya karakter Id NuGet, versi angka bertitik")
    void idAndVersion() {
        assertEquals(LibraryInspector.NOT_LIBRARY, rejected(zip("A.nuspec", nuspec("", "1.0.0", "OpenStudio Library", ""),
                "lib/net45/A.dll", DLL), "a.nupkg").errorCode());
        assertEquals(LibraryInspector.INVALID_NAME, rejected(zip("A.nuspec", nuspec("Tagihan Umum", "1.0.0",
                "OpenStudio Library", ""), "lib/net45/A.dll", DLL), "a.nupkg").errorCode());
        assertEquals(PackageNaming.INVALID_VERSION, rejected(zip("A.nuspec", nuspec("A", "versi-satu",
                "OpenStudio Library", ""), "lib/net45/A.dll", DLL), "a.nupkg").errorCode());
    }

    @Test
    @DisplayName("aturan arsip yang sama dengan paket proses: jalur keluar folder dan DOCTYPE ditolak")
    void unsafeArchive() {
        assertEquals(PackageInspector.UNSAFE_PATH, rejected(zip("A.nuspec", nuspec("A", "1.0.0", "OpenStudio Library", ""),
                "../lib/net45/A.dll", DLL), "a.nupkg").errorCode());

        String doctype = "<?xml version=\"1.0\"?><!DOCTYPE package [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<package><metadata><id>&x;</id><version>1.0.0</version><tags>OpenStudio Library</tags></metadata></package>";
        assertEquals(PackageInspector.CORRUPT, rejected(zip("A.nuspec", doctype, "lib/net45/A.dll", DLL), "a.nupkg").errorCode());
    }

    // -------------------------------------------------------------------- di Unggah Paket

    @Test
    @DisplayName("library yang diunggah sebagai paket proses: diarahkan ke Tenant › Library")
    void libraryAsProcessPackage() {
        ApiException e = assertThrows(ApiException.class,
                () -> PackageInspector.inspect(library("Tagihan.Umum", "1.0.0", ""), "Tagihan.Umum.1.0.0.nupkg"));

        assertEquals(PackageInspector.IS_LIBRARY, e.errorCode());
        assertTrue(e.getMessage().contains("Tenant › Library"), e.getMessage());
    }

    @Test
    @DisplayName("Unggah Paket: dependensi yang ada di feed library tidak diperingatkan sebagai activity")
    void processDependenciesOnLibraries() {
        String project = "{\"name\":\"Tagihan\",\"main\":\"Main.xaml\",\"dependencies\":"
                + "{\"Tagihan.Umum\":\"1.2.0\",\"OpenStudio.Mail.Activities\":\"2.0.1\"}}";
        byte[] file = zip("Main.xaml", MAIN, "project.json", project);

        PackageInspector.Inspection withFeed = PackageInspector.inspect(file, "Tagihan.1.0.0.zip",
                id -> id.equalsIgnoreCase("tagihan.umum"));
        PackageInspector.Inspection withoutFeed = PackageInspector.inspect(file, "Tagihan.1.0.0.zip");

        assertEquals(Map.of("Tagihan.Umum", "1.2.0", "OpenStudio.Mail.Activities", "2.0.1"), withFeed.dependencyVersions());
        assertEquals(1, withFeed.warnings().size());
        assertTrue(withFeed.warnings().getFirst().contains("OpenStudio.Mail.Activities 2.0.1"), withFeed.warnings().toString());
        assertFalse(withFeed.warnings().getFirst().contains("Tagihan.Umum"), withFeed.warnings().toString());
        assertTrue(withoutFeed.warnings().getFirst().contains("Tagihan.Umum"), withoutFeed.warnings().toString());
    }

    @Test
    @DisplayName("dependensi dan penanda library project.json dari paket yang tersimpan; tanpa project.json atau rusak: kosong")
    void storedPackageDependencies() {
        PackageInspector.ProjectDependencies stored = PackageInspector.projectDependencies(zip("Main.xaml", MAIN,
                "project.json", "{\"dependencies\":{\"Tagihan.Umum\":\"1.2.0\"},\"libraries\":[\"Tagihan.Umum\",\"tagihan.umum\",3,\"\"]}"));
        assertEquals(Map.of("Tagihan.Umum", "1.2.0"), stored.dependencies());
        assertEquals(List.of("Tagihan.Umum"), stored.libraries(), "hanya teks, Id ganda sekali");

        assertEquals(PackageInspector.ProjectDependencies.NONE, PackageInspector.projectDependencies(zip("Main.xaml", MAIN)));
        assertEquals(PackageInspector.ProjectDependencies.NONE, PackageInspector.projectDependencies(zip("project.json", "{ bukan json")));
        assertEquals(PackageInspector.ProjectDependencies.NONE, PackageInspector.projectDependencies("bukan zip".getBytes()));
        assertEquals(PackageInspector.ProjectDependencies.NONE, PackageInspector.projectDependencies(null));
        assertEquals(List.of(), PackageInspector.projectDependencies(zip("project.json",
                "{\"dependencies\":{},\"libraries\":\"Tagihan.Umum\"}")).libraries(), "bukan larik: diabaikan");
    }

    @Test
    @DisplayName("Unggah Paket: workflow yang memakai activity library (assembly=Id) tidak diperingatkan sebagai pustaka asing")
    void libraryAssemblyInWorkflow() {
        String xaml = MAIN.replace("xmlns:c=", "xmlns:p=\"clr-namespace:ContohPajak;assembly=ContohPajak\" xmlns:c=");
        String project = "{\"name\":\"Konsumen\",\"main\":\"Main.xaml\",\"dependencies\":{\"ContohPajak\":\"[1.2.0]\"}}";

        PackageInspector.Inspection inFeed = PackageInspector.inspect(zip("Main.xaml", xaml, "project.json", project),
                "Konsumen.1.0.0.zip", id -> id.equalsIgnoreCase("contohpajak"));
        PackageInspector.Inspection notInFeed = PackageInspector.inspect(zip("Main.xaml", xaml, "project.json", project),
                "Konsumen.1.0.0.zip");

        assertTrue(inFeed.warnings().isEmpty(), inFeed.warnings().toString());
        assertTrue(notInFeed.warnings().stream().anyMatch(w -> w.contains("bukan bawaan OpenStudio: ContohPajak")),
                notInFeed.warnings().toString());
    }

    @Test
    @DisplayName("Unggah Paket: Id di \"libraries\" project.json adalah library walau belum ada di feed")
    void declaredLibrariesAreNotActivities() {
        String xaml = MAIN.replace("xmlns:c=", "xmlns:p=\"clr-namespace:ContohPajak;assembly=ContohPajak\" xmlns:c=");
        String project = "{\"name\":\"Konsumen\",\"main\":\"Main.xaml\",\"dependencies\":{\"ContohPajak\":\"[1.2.0]\","
                + "\"OpenStudio.Mail.Activities\":\"2.0.1\"},\"libraries\":[\"contohpajak\"]}";

        PackageInspector.Inspection p = PackageInspector.inspect(zip("Main.xaml", xaml, "project.json", project),
                "Konsumen.1.0.0.zip");

        assertEquals(List.of("contohpajak"), p.declaredLibraries());
        assertEquals(1, p.warnings().size(), p.warnings().toString());
        assertTrue(p.warnings().getFirst().contains("activity: OpenStudio.Mail.Activities 2.0.1"), p.warnings().toString());
    }
}
