package id.jakforge.openorchestrator.service;

import id.jakforge.openorchestrator.common.ApiException;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Memeriksa library OpenStudio yang diunggah ke feed library (V20).
 *
 * <p>Library adalah proyek OpenStudio yang workflow-nya dikompilasi menjadi
 * activity. Hasil Terbitkan-nya sebuah .nupkg: .nuspec bertag
 * {@code OpenStudio Library}, dependensinya (bila ada) di
 * {@code <dependencies>}, dan TEPAT SATU {@code lib/net45/<Id>.dll} — workflow-nya
 * tersemat di DLL itu. Robot menaruh DLL itu di folder extensions sebelum
 * menjalankan job yang memakainya.
 *
 * <p>Yang diperiksa: arsip yang utuh dan aman dibuka (aturan yang sama dengan
 * paket proses, {@link PackageInspector}), .nuspec yang terbaca tanpa DTD, tag
 * library, dan DLL-nya. Isi DLL tidak dibuka di sini — itu kode .NET; yang
 * menjaganya adalah izin unggah, audit, dan sidik SHA-256 yang dicocokkan robot.
 */
final class LibraryInspector {

    static final String FILE_TYPE = "LIBRARY_FILE_TYPE";
    static final String NOT_LIBRARY = "LIBRARY_NOT_LIBRARY";
    static final String ASSEMBLY = "LIBRARY_ASSEMBLY";
    static final String INVALID_NAME = "LIBRARY_INVALID_NAME";

    static final String TARGET_FOLDER = "lib/net45/";
    private static final Set<String> LIBRARY_TAGS = Set.of("openstudio", "library");
    /** Id NuGet: huruf, angka, titik, garis bawah, tanda hubung — juga nama berkas DLL dan bagian alamat unduhan. */
    private static final Pattern NUGET_ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");
    private static final int MAX_AUTHORS_LENGTH = 400;
    private static final int MAX_DESCRIPTION_LENGTH = 400;

    /**
     * @param assemblyFile jalur DLL di dalam .nupkg, mis. lib/net45/Tagihan.Umum.dll
     * @param dependencies dependensi dari .nuspec, Id → versi
     * @param warnings     yang tidak menghalangi, untuk ditampilkan ke pengunggah
     */
    record Inspection(String name, String version, String description, String authors, String assemblyFile,
                      Map<String, String> dependencies, List<String> warnings) {
    }

    private LibraryInspector() {
    }

    /** Tag .nuspec dipisah spasi: "OpenStudio Library" berarti dua tag, OpenStudio dan Library. */
    static boolean hasLibraryTag(String tags) {
        if (tags == null) return false;
        Set<String> words = new java.util.HashSet<>(Arrays.asList(tags.toLowerCase(Locale.ROOT).split("[\\s,;]+")));
        return words.containsAll(LIBRARY_TAGS);
    }

    static Inspection inspect(byte[] file, String fileName) {
        String shownName = fileName == null || fileName.isBlank() ? "library" : fileName;

        if (!shownName.toLowerCase(Locale.ROOT).endsWith(".nupkg")) {
            throw ApiException.badRequest("Berkas '" + shownName + "' bukan library. Yang diterima: .nupkg hasil"
                    + " Terbitkan proyek Library di OpenStudio.").withCode(FILE_TYPE);
        }

        return PackageInspector.withZip(file, shownName, zip -> inspect(zip, shownName));
    }

    private static Inspection inspect(ZipFile zip, String shownName) throws IOException {
        Map<String, ZipEntry> entries = PackageInspector.readEntries(zip, shownName);

        String nuspecName = PackageInspector.rootNuspec(entries);
        if (nuspecName == null) {
            throw ApiException.badRequest("'" + shownName + "' bukan paket NuGet: tidak ada .nuspec di akarnya.")
                    .withCode(NOT_LIBRARY);
        }
        Nuspec nuspec = readNuspec(zip, entries.get(nuspecName), shownName);

        // Isi lib/, dengan nama bagian OPC (%20) dikembalikan ke aslinya.
        List<String> assemblies = new ArrayList<>();
        boolean hasWorkflow = false;
        for (String path : entries.keySet()) {
            String decoded = PackageInspector.unescapePart(path);
            String lower = decoded.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".dll")) assemblies.add(decoded);
            if (lower.startsWith("lib/") && lower.endsWith(".xaml")) hasWorkflow = true;
        }

        if (!hasLibraryTag(nuspec.tags())) {
            if (hasWorkflow) {
                throw ApiException.badRequest("'" + shownName + "' adalah paket proses, bukan library. Unggah lewat"
                        + " Tenant › Paket › Unggah Paket.").withCode(NOT_LIBRARY);
            }
            throw ApiException.badRequest("'" + shownName + "' bukan library OpenStudio: .nuspec-nya tidak bertag"
                    + " 'OpenStudio Library'. Paket activity dipasang lewat OpenStudio › Kelola Paket.")
                    .withCode(NOT_LIBRARY);
        }

        String name = nuspec.id();
        String version = nuspec.version();
        if (name == null || version == null) {
            throw ApiException.badRequest(".nuspec di '" + shownName + "' tidak menyebut " + (name == null ? "id" : "version")
                    + " di <metadata>.").withCode(NOT_LIBRARY);
        }
        PackageNaming.validate(name, version);
        if (!NUGET_ID.matcher(name).matches()) {
            throw ApiException.badRequest("Id library tidak sah: '" + name + "'. Yang boleh hanya huruf, angka, titik,"
                    + " garis bawah, dan tanda hubung.").withCode(INVALID_NAME);
        }

        // Tepat satu DLL, langsung di lib/net45/: itulah yang ditaruh robot di
        // folder extensions. DLL lain — di target lain atau di subfolder — tidak
        // akan pernah dimuat, dan diam-diam hilang saat dijalankan.
        if (assemblies.size() != 1 || !isTargetAssembly(assemblies.getFirst())) {
            throw ApiException.badRequest("Library '" + shownName + "' harus berisi tepat satu " + TARGET_FOLDER
                    + "<Id>.dll" + (assemblies.isEmpty() ? "; tidak ada DLL di dalamnya."
                    : "; ditemukan: " + String.join(", ", assemblies.stream().limit(5).toList())
                    + (assemblies.size() > 5 ? ", dan " + (assemblies.size() - 5) + " lainnya" : "") + "."))
                    .withCode(ASSEMBLY);
        }
        String assemblyFile = assemblies.getFirst();
        ZipEntry assembly = entries.get(entries.keySet().stream()
                .filter(p -> PackageInspector.unescapePart(p).equals(assemblyFile)).findFirst().orElseThrow());

        byte[] header = readHeader(zip, assembly);
        if (header.length < 2 || header[0] != 'M' || header[1] != 'Z') {
            throw ApiException.badRequest(assemblyFile + " di '" + shownName + "' bukan DLL .NET.").withCode(ASSEMBLY);
        }

        List<String> warnings = new ArrayList<>();
        String expected = name + ".dll";
        String actual = assemblyFile.substring(TARGET_FOLDER.length());
        if (!actual.equalsIgnoreCase(expected)) {
            warnings.add("Nama DLL-nya " + actual + ", bukan " + expected + " seperti hasil Terbitkan OpenStudio.");
        }

        return new Inspection(name, version, cut(nuspec.description(), MAX_DESCRIPTION_LENGTH),
                cut(nuspec.authors(), MAX_AUTHORS_LENGTH), assemblyFile, nuspec.dependencies(), List.copyOf(warnings));
    }

    private static boolean isTargetAssembly(String path) {
        return path.regionMatches(true, 0, TARGET_FOLDER, 0, TARGET_FOLDER.length())
                && path.indexOf('/', TARGET_FOLDER.length()) < 0;
    }

    /** Dua bita pertama saja: cukup untuk tanda "MZ" berkas PE. */
    private static byte[] readHeader(ZipFile zip, ZipEntry entry) throws IOException {
        try (var in = zip.getInputStream(entry)) {
            return in.readNBytes(2);
        }
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    // -------------------------------------------------------------------- .nuspec

    private record Nuspec(String id, String version, String description, String authors, String tags,
                          Map<String, String> dependencies) {
    }

    /**
     * &lt;metadata&gt; dan &lt;dependency id version&gt; — langsung di
     * &lt;dependencies&gt; atau di dalam &lt;group targetFramework&gt; — apa pun
     * namespace .nuspec-nya. Dependensi yang disebut di beberapa grup diambil
     * dari yang pertama.
     */
    private static Nuspec readNuspec(ZipFile zip, ZipEntry entry, String shownName) throws IOException {
        byte[] content = PackageInspector.readBytes(zip, entry, entry.getName(), shownName,
                PackageInspector.MAX_TEXT_FILE_BYTES);
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> dependencies = new LinkedHashMap<>();

        XMLStreamReader reader = null;
        try {
            reader = PackageInspector.xmlFactory().createXMLStreamReader(new ByteArrayInputStream(content));
            List<String> path = new ArrayList<>();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DOCTYPE tidak diizinkan");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    path.add(reader.getLocalName());
                    boolean inMetadata = path.size() >= 2 && path.get(1).equals("metadata");

                    if (inMetadata && path.size() == 3
                            && Set.of("id", "version", "description", "authors", "tags").contains(path.get(2))) {
                        values.put(path.get(2), reader.getElementText().trim());
                        path.removeLast();
                    } else if (inMetadata && path.size() >= 4 && path.get(2).equals("dependencies")
                            && reader.getLocalName().equals("dependency")) {
                        String id = reader.getAttributeValue(null, "id");
                        String version = reader.getAttributeValue(null, "version");
                        if (id != null && !id.isBlank()) {
                            boolean known = dependencies.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(id.trim()));
                            if (!known) dependencies.put(id.trim(), version == null ? "" : version.trim());
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    path.removeLast();
                }
            }
        } catch (XMLStreamException e) {
            throw PackageInspector.corrupt(shownName, ".nuspec tidak terbaca: " + PackageInspector.firstLine(e.getMessage()));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Sudah selesai dibaca.
                }
            }
        }

        return new Nuspec(blankToNull(values.get("id")), blankToNull(values.get("version")),
                blankToNull(values.get("description")), blankToNull(values.get("authors")), values.get("tags"),
                java.util.Collections.unmodifiableMap(dependencies));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
