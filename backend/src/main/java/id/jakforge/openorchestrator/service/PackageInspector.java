package id.jakforge.openorchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.jakforge.openorchestrator.common.ApiException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Memeriksa paket yang diunggah manual (V17) dan menyiapkannya dalam tata
 * letak yang dibaca robot.
 *
 * <p>Robot (OpenStudio.Robot.Core: {@code PackageCache.ExtractSafely} lalu
 * {@code EntryPointResolver}) mengekstrak isi paket APA ADANYA dan mencari
 * workflow di AKAR-nya — tata letak zip yang dikirim Studio saat Terbitkan ke
 * Orchestrator. Paket .nupkg buatan Studio (Terbitkan → Folder lokal) menaruh
 * proyeknya di {@code lib/net45/}; disimpan mentah, robot tidak akan menemukan
 * apa pun untuk dijalankan. Karena itu isi {@code lib/<target>/} dipindah ke
 * akar, dan yang disimpan adalah zip proyek yang sama dengan hasil Terbitkan.
 *
 * <p>Yang DIPERIKSA adalah yang benar-benar dibutuhkan robot, dengan urutan
 * yang sama dengan robot: arsip yang utuh dan aman diekstrak, workflow XAML
 * yang terbaca, dan titik masuk yang bisa ditentukan. Yang TIDAK bisa
 * diperiksa server — activity yang terpasang di mesin robot — dilaporkan
 * sebagai peringatan, bukan dijanjikan jalan. Paket UiPath Studio ditolak:
 * activity UiPath tidak ada di robot ini.
 */
final class PackageInspector {

    /** Jumlah isi sesudah dibuka: menahan "bom zip" kecil yang mengembang tanpa batas. */
    static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;
    static final int MAX_ENTRIES = 20_000;
    /** Satu workflow atau project.json yang dibaca untuk diperiksa. */
    static final int MAX_TEXT_FILE_BYTES = 32 * 1024 * 1024;
    /** Jalur di dalam paket yang mulai berisiko melewati MAX_PATH di robot. */
    static final int LONG_PATH = 160;

    static final String FILE_TYPE = "PACKAGE_FILE_TYPE";
    static final String CORRUPT = "PACKAGE_CORRUPT";
    static final String UNSAFE_PATH = "PACKAGE_UNSAFE_PATH";
    static final String NOT_PROCESS = "PACKAGE_NOT_PROCESS";
    static final String NO_WORKFLOW = "PACKAGE_NO_WORKFLOW";
    static final String ENTRY_POINT = "PACKAGE_ENTRY_POINT";
    static final String INVALID_XAML = "PACKAGE_INVALID_XAML";
    static final String INVALID_PROJECT = "PACKAGE_INVALID_PROJECT";
    static final String UIPATH = "PACKAGE_UIPATH_UNSUPPORTED";
    static final String TOO_LARGE = "PACKAGE_TOO_LARGE";
    /** V20: library OpenStudio yang diunggah sebagai paket proses — tempatnya feed library. */
    static final String IS_LIBRARY = "PACKAGE_IS_LIBRARY";

    private static final String WORKFLOW_NAMESPACE = "http://schemas.microsoft.com/netfx/2009/xaml/activities";
    private static final String UIPATH_NAMESPACE = "http://schemas.uipath.com/workflow/activities";
    private static final String PROJECT_JSON = "project.json";
    private static final String MAIN_XAML = "main.xaml";

    /**
     * Pustaka yang ada di robot OpenStudio: .NET Framework/WF, Studio, dan
     * paket activity bawaannya. Yang lain belum tentu ada — dilaporkan, tidak ditolak.
     *
     * <p>"OpenStudio" tanpa titik: cocok untuk {@code OpenStudio} dan {@code OpenStudio.*}
     * (nama sejak ganti nama Studio 16 Okt 2026). Nama lama — OpenRPA, Custom., JakForge. —
     * tetap dikenal untuk paket dari Studio sebelum itu.
     */
    private static final List<String> KNOWN_ASSEMBLY_PREFIXES = List.of(
            "System", "mscorlib", "netstandard", "Microsoft.", "PresentationFramework", "PresentationCore",
            "WindowsBase", "UIAutomation", "OpenStudio", "OpenRPA", "Custom.", "JakForge.", "Newtonsoft.Json", "Xceed.",
            "DotNetProjects.", "Forge.Forms", "Open3270", "WinFX3270Library", "NSspi", "LiteDB");

    private static final Pattern ASSEMBLY = Pattern.compile("assembly=([^;\\s]+)");
    /** "Tagihan.1.0.3" → Tagihan, 1.0.3 — nama berkas unduhan Orchestrator dan .nupkg. */
    private static final Pattern NAME_DOT_VERSION =
            Pattern.compile("(.+?)\\.(\\d+(?:\\.\\d+){1,3}(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?)");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Tata letak berkas yang diunggah. */
    enum Layout {
        /** Zip proyek dengan workflow di akarnya — disimpan apa adanya. */
        ZIP,
        /** Zip berisi SATU folder proyek — isinya dipindah ke akar. */
        ZIP_IN_FOLDER,
        /** Paket NuGet (.nupkg) — isi lib/&lt;target&gt;/ dipindah ke akar. */
        NUPKG
    }

    /**
     * @param content            isi yang disimpan: zip proyek, workflow di akarnya
     * @param dependencies       dependensi dari project.json, "Id versi" (activity dan library)
     * @param dependencyVersions dependensi yang sama sebagai Id → versi, untuk disimpan dan dicocokkan dengan feed library
     * @param declaredLibraries  Id di "libraries" project.json (V21): dependensi yang ditandai Studio sebagai library
     * @param warnings           yang tidak bisa dipastikan server, untuk ditampilkan ke pengunggah
     */
    record Inspection(String name, String version, String description, String entryPoint, byte[] content,
                      Layout layout, List<String> dependencies, Map<String, String> dependencyVersions,
                      List<String> declaredLibraries, List<String> warnings) {

        boolean converted() {
            return layout != Layout.ZIP;
        }
    }

    private PackageInspector() {
    }

    static Inspection inspect(byte[] file, String fileName) {
        return inspect(file, fileName, id -> false);
    }

    /**
     * @param isLibrary Id yang ada di feed library (V20): dipasang robot dari
     *                  Orchestrator, jadi tidak diperingatkan sebagai activity
     *                  atau pustaka yang harus sudah ada di mesin robot. Id yang
     *                  ditandai di "libraries" project.json juga tidak.
     */
    static Inspection inspect(byte[] file, String fileName, Predicate<String> isLibrary) {
        String shownName = fileName == null || fileName.isBlank() ? "paket" : fileName;
        String lower = shownName.toLowerCase(Locale.ROOT);

        if (!lower.endsWith(".nupkg") && !lower.endsWith(".zip")) {
            throw ApiException.badRequest("Berkas '" + shownName + "' bukan paket. Yang diterima: .nupkg dari OpenStudio"
                    + " (Terbitkan → Folder lokal) atau .zip proyek yang diunduh dari Open Orchestrator.").withCode(FILE_TYPE);
        }

        return withZip(file, shownName, zip -> inspect(zip, file, shownName, isLibrary));
    }

    /** Membaca arsip zip di memori; yang memeriksanya. */
    interface ZipReader<T> {
        T read(ZipFile zip) throws IOException;
    }

    /**
     * Arsip di memori dibuka sebagai ZipFile — lewat berkas sementara, karena
     * hanya ZipFile yang membaca direktori pusat zip (ukuran yang dinyatakan,
     * entri ganda) sebelum isinya dibuka.
     */
    static <T> T withZip(byte[] file, String shownName, ZipReader<T> reader) {
        if (file == null || file.length < 4 || file[0] != 'P' || file[1] != 'K') {
            throw corrupt(shownName, "bukan arsip zip");
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile("oo-paket-", ".zip");
            Files.write(temporary, file);

            try (ZipFile zip = open(temporary, shownName)) {
                return reader.read(zip);
            }
        } catch (IOException e) {
            throw corrupt(shownName, e.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Berkas sementara sistem; dibersihkan sistem operasinya.
                }
            }
        }
    }

    /**
     * @param dependencies "dependencies" project.json, Id → versi
     * @param libraries    "libraries" project.json (V21): Id dependensi yang berupa library; kosong di paket Studio lama
     */
    record ProjectDependencies(Map<String, String> dependencies, List<String> libraries) {

        static final ProjectDependencies NONE = new ProjectDependencies(Map.of(), List.of());
    }

    /**
     * Dependensi project.json dari isi paket yang SUDAH tersimpan (zip proyek,
     * project.json di akarnya). Paket tanpa project.json, atau yang tidak
     * terbaca, dianggap tanpa dependensi: yang dipakai di sini hanya untuk
     * mencari library yang dibutuhkan, dan paket itu sudah lolos saat terbit.
     */
    static ProjectDependencies projectDependencies(byte[] content) {
        if (content == null) return ProjectDependencies.NONE;

        try {
            return withZip(content, "paket", zip -> {
                ZipEntry entry = readEntries(zip, "paket").get(PROJECT_JSON);
                if (entry == null) return ProjectDependencies.NONE;
                JsonNode projectJson = JSON.readTree(readText(zip, entry, PROJECT_JSON, "paket"));
                return new ProjectDependencies(dependencies(projectJson), declaredLibraries(projectJson));
            });
        } catch (RuntimeException e) {
            return ProjectDependencies.NONE;
        }
    }

    /**
     * Nama entri yang tidak bertanda UTF-8 dibaca sebagai CP437, sesuai
     * spesifikasi zip (Explorer lama dan banyak alat menulisnya begitu); yang
     * bertanda UTF-8 tetap UTF-8.
     */
    private static ZipFile open(Path path, String shownName) throws IOException {
        try {
            return new ZipFile(path.toFile(), Charset.forName("IBM437"));
        } catch (IllegalArgumentException e) {
            try {
                return new ZipFile(path.toFile(), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException again) {
                throw corrupt(shownName, "nama berkas di dalamnya tidak terbaca");
            }
        }
    }

    private static Inspection inspect(ZipFile zip, byte[] original, String shownName, Predicate<String> isLibrary)
            throws IOException {
        Map<String, ZipEntry> entries = readEntries(zip, shownName);

        List<String> warnings = new ArrayList<>();
        Layout layout;
        String nuspecName = rootNuspec(entries);
        Map<String, ZipEntry> project;
        Map<String, String> nuspec = Map.of();

        // Paket NuGet: .nuspec di akar dan isinya di lib/. Folder proyek yang
        // kebetulan menyimpan .nuspec, tanpa lib/, tetap zip proyek biasa.
        boolean hasLib = entries.keySet().stream().anyMatch(p -> p.regionMatches(true, 0, "lib/", 0, 4));
        if (nuspecName != null && (hasLib || !hasProjectAtRoot(entries.keySet()))) {
            layout = Layout.NUPKG;
            if (nuspecName != null) nuspec = readNuspec(zip, entries.get(nuspecName), shownName);
            project = nupkgProject(entries, shownName, LibraryInspector.hasLibraryTag(nuspec.get("tags")));
        } else if (hasProjectAtRoot(entries.keySet())) {
            layout = Layout.ZIP;
            project = entries;
        } else {
            String folder = singleProjectFolder(entries.keySet());
            if (folder == null) {
                throw ApiException.badRequest("Tidak ada workflow (.xaml) atau project.json di akar '" + shownName
                        + "'. Kemas ISI folder proyek, bukan folder di atasnya.").withCode(NO_WORKFLOW);
            }
            layout = Layout.ZIP_IN_FOLDER;
            project = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, ZipEntry> e : entries.entrySet()) {
                project.put(e.getKey().substring(folder.length()), e.getValue());
            }
        }

        // ---------------------------------------------------------------- project.json
        JsonNode projectJson = null;
        ZipEntry projectEntry = project.get(PROJECT_JSON);
        if (projectEntry != null) {
            try {
                projectJson = JSON.readTree(readText(zip, projectEntry, PROJECT_JSON, shownName));
            } catch (IOException e) {
                throw ApiException.badRequest("project.json di '" + shownName + "' tidak bisa dibaca: "
                        + firstLine(e.getMessage())).withCode(INVALID_PROJECT);
            }
            if (projectJson == null || !projectJson.isObject()) {
                throw ApiException.badRequest("project.json di '" + shownName + "' bukan objek JSON.")
                        .withCode(INVALID_PROJECT);
            }
        }

        Map<String, String> dependencies = dependencies(projectJson);
        List<String> declaredLibraries = declaredLibraries(projectJson);
        // Library — di feed, atau ditandai Studio di "libraries" — dipasang robot
        // sendiri (V20/V21): bukan activity, dan DLL-nya bukan pustaka asing.
        Predicate<String> library = id -> isLibrary.test(id) || declaredLibraries.stream().anyMatch(id::equalsIgnoreCase);
        List<String> uipathEvidence = new ArrayList<>();
        dependencies.keySet().stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith("uipath."))
                .forEach(id -> uipathEvidence.add("dependency " + id));

        // ---------------------------------------------------------------- workflow
        List<String> rootXaml = new ArrayList<>();
        Set<String> unknownAssemblies = new LinkedHashSet<>();
        Map<String, String> rootElements = new LinkedHashMap<>();

        for (Map.Entry<String, ZipEntry> e : project.entrySet()) {
            String path = e.getKey();
            if (!path.toLowerCase(Locale.ROOT).endsWith(".xaml")) continue;
            if (!path.contains("/")) rootXaml.add(path);

            Xaml xaml = readXaml(zip, e.getValue(), path, shownName);
            rootElements.put(path, xaml.rootElement());
            for (String ns : xaml.namespaces()) {
                if (ns.equals(UIPATH_NAMESPACE)) uipathEvidence.add("namespace UiPath di " + path);
                Matcher m = ASSEMBLY.matcher(ns);
                if (!m.find()) continue;
                String assembly = m.group(1);
                if (assembly.toLowerCase(Locale.ROOT).startsWith("uipath")) {
                    uipathEvidence.add("pustaka " + assembly + " di " + path);
                } else if (KNOWN_ASSEMBLY_PREFIXES.stream().noneMatch(assembly::startsWith) && !library.test(assembly)) {
                    unknownAssemblies.add(assembly);
                }
            }
        }

        if (!uipathEvidence.isEmpty()) {
            throw ApiException.badRequest("Paket '" + shownName + "' dibuat untuk UiPath ("
                    + String.join("; ", uipathEvidence.stream().distinct().limit(3).toList())
                    + "). Robot Open Orchestrator menjalankan workflow OpenStudio, dan activity UiPath tidak ada di"
                    + " sana. Buka proyeknya di OpenStudio, ganti activity-nya, lalu terbitkan dari sana.").withCode(UIPATH);
        }

        String entryPoint = entryPoint(project, projectJson, rootXaml, warnings, shownName);
        String root = rootElements.get(entryPoint);
        if (!"Activity".equals(root)) {
            throw ApiException.badRequest("Titik masuk '" + entryPoint + "' bukan workflow: akar XAML-nya <" + root
                    + ">, bukan <Activity>.").withCode(INVALID_XAML);
        }

        // ---------------------------------------------------------------- nama, versi
        String stem = shownName.replaceFirst("(?i)\\.(nupkg|zip)$", "");
        Matcher fromFileName = NAME_DOT_VERSION.matcher(stem);
        boolean fileNameHasVersion = fromFileName.matches();

        String name = firstText(text(projectJson, "name"), nuspec.get("id"),
                fileNameHasVersion ? fromFileName.group(1) : stem);
        String version = firstText(nuspec.get("version"), fileNameHasVersion ? fromFileName.group(2) : null,
                text(projectJson, "version"), text(projectJson, "projectVersion"));

        if (version == null) {
            throw ApiException.badRequest("Versi paket '" + shownName + "' tidak diketahui. Unggah .nupkg (versinya"
                    + " ada di .nuspec), atau beri nama berkas zip <nama>.<versi>.zip, mis. Tagihan.1.0.3.zip.")
                    .withCode(PackageNaming.INVALID_VERSION);
        }
        PackageNaming.validate(name, version);

        String description = firstText(nuspec.get("description"), text(projectJson, "description"));
        if (description != null && description.length() > 400) description = description.substring(0, 400);

        // Selain library, dependensi adalah activity yang harus sudah terpasang di mesin robot.
        List<String> activities = dependencies.entrySet().stream()
                .filter(d -> !library.test(d.getKey()))
                .map(d -> d.getKey() + (d.getValue().isBlank() ? "" : " " + d.getValue())).toList();
        if (!activities.isEmpty()) {
            warnings.add("Paket ini membutuhkan activity: " + String.join(", ", activities)
                    + ". Robot tidak memasangnya otomatis; pastikan sudah ada di mesin robot.");
        }
        if (!unknownAssemblies.isEmpty()) {
            warnings.add("Workflow merujuk pustaka yang bukan bawaan OpenStudio: "
                    + String.join(", ", unknownAssemblies) + ". Pastikan pustaka itu ada di mesin robot.");
        }

        // Robot .NET Framework 4.6.2 dibatasi 260 karakter per jalur, dan
        // folder kerja job-nya sendiri sudah memakan sekitar 80.
        project.keySet().stream().filter(p -> p.length() > LONG_PATH).findFirst().ifPresent(p -> warnings.add(
                "Jalur berkas di paket sangat panjang (" + p.length() + " karakter, mis. " + p
                        + "); di robot bisa melewati batas 260 karakter Windows."));

        byte[] content = layout == Layout.ZIP ? original : repack(zip, project, shownName);

        return new Inspection(name, version, description, entryPoint, content, layout,
                dependencies.entrySet().stream().map(d -> (d.getKey() + " " + d.getValue()).trim()).toList(),
                java.util.Collections.unmodifiableMap(dependencies), declaredLibraries, List.copyOf(warnings));
    }

    // -------------------------------------------------------------------- entri

    /**
     * Semua berkas di arsip (tanpa entri folder), dengan "/" sebagai pemisah.
     * Jalur yang bisa keluar dari folder tujuan ditolak di sini — robot juga
     * menolaknya, tetapi baru saat job sudah berjalan.
     *
     * <p>Kunci tidak peka huruf besar-kecil: robot mengekstrak di Windows,
     * dan dua entri yang hanya berbeda huruf saling menimpa di sana.
     */
    static Map<String, ZipEntry> readEntries(ZipFile zip, String shownName) {
        Map<String, ZipEntry> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        long declared = 0;
        int count = 0;

        try {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw ApiException.badRequest("Paket '" + shownName + "' berisi lebih dari " + MAX_ENTRIES
                            + " berkas.").withCode(TOO_LARGE);
                }
                if (entry.isDirectory()) continue;

                String path = safePath(entry.getName(), shownName);
                if (entries.putIfAbsent(path, entry) != null) {
                    throw ApiException.badRequest("Paket '" + shownName + "' berisi berkas ganda: " + path + ".")
                            .withCode(CORRUPT);
                }

                if (entry.getSize() > 0) declared += entry.getSize();
                if (declared > MAX_UNCOMPRESSED_BYTES) throw tooLargeUncompressed(shownName);
            }
        } catch (IllegalArgumentException e) {
            throw corrupt(shownName, "nama berkas di dalamnya tidak terbaca");
        }

        if (entries.isEmpty()) throw corrupt(shownName, "arsipnya kosong");

        return entries;
    }

    /**
     * Jalur yang aman diekstrak robot di Windows: relatif, tidak keluar
     * folder, dan tanpa karakter yang ditolak Path.GetFullPath .NET
     * Framework (&lt;&gt;:"|?* dan karakter kendali).
     */
    static String safePath(String raw, String shownName) {
        String path = raw.replace('\\', '/');
        boolean unsafe = path.startsWith("/") || path.chars().anyMatch(c -> c < 32 || "<>:\"|?*".indexOf(c) >= 0);

        for (String segment : path.split("/", -1)) {
            if (segment.equals("..")) unsafe = true;
        }

        if (unsafe) {
            throw ApiException.badRequest("Paket '" + shownName + "' berisi jalur yang tidak diizinkan: " + raw + ".")
                    .withCode(UNSAFE_PATH);
        }

        return path.replaceAll("/{2,}", "/").replaceFirst("^\\./", "");
    }

    static String rootNuspec(Map<String, ZipEntry> entries) {
        return entries.keySet().stream()
                .filter(p -> !p.contains("/") && p.toLowerCase(Locale.ROOT).endsWith(".nuspec"))
                .findFirst().orElse(null);
    }

    private static boolean hasProjectAtRoot(Set<String> paths) {
        return paths.stream().anyMatch(p -> !p.contains("/")
                && (p.equalsIgnoreCase(PROJECT_JSON) || p.toLowerCase(Locale.ROOT).endsWith(".xaml")));
    }

    /** "Proyek/" kalau SEMUA berkas ada di satu folder yang berisi proyek; selain itu null. */
    private static String singleProjectFolder(Set<String> paths) {
        String folder = null;
        for (String p : paths) {
            int slash = p.indexOf('/');
            if (slash < 0) return null;
            String first = p.substring(0, slash + 1);
            if (folder == null) folder = first;
            else if (!folder.equalsIgnoreCase(first)) return null;
        }
        if (folder == null) return null;

        String prefix = folder;
        Set<String> inside = new java.util.HashSet<>();
        for (String p : paths) inside.add(p.substring(prefix.length()));
        return hasProjectAtRoot(inside) ? prefix : null;
    }

    /**
     * Isi lib/&lt;target&gt;/ paket NuGet, dengan nama bagian OPC yang
     * di-escape (spasi = %20) dikembalikan ke nama aslinya — sama dengan yang
     * dilakukan Open Assistant (ProjectPackage.Extract) untuk feed lokal.
     */
    private static Map<String, ZipEntry> nupkgProject(Map<String, ZipEntry> entries, String shownName,
                                                      boolean libraryTag) {
        Map<String, Map<String, ZipEntry>> perTarget = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean hasLibrary = false;

        for (Map.Entry<String, ZipEntry> e : entries.entrySet()) {
            String decoded = unescapePart(e.getKey());
            String[] parts = decoded.split("/", 3);
            if (parts.length < 3 || !parts[0].equalsIgnoreCase("lib") || parts[2].isEmpty()) continue;

            String relative = safePath(parts[2], shownName);
            if (relative.toLowerCase(Locale.ROOT).endsWith(".dll")) hasLibrary = true;

            Map<String, ZipEntry> files = perTarget.computeIfAbsent(parts[1], k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
            if (files.putIfAbsent(relative, e.getValue()) != null) {
                throw ApiException.badRequest("Paket '" + shownName + "' berisi berkas ganda: " + relative + ".")
                        .withCode(CORRUPT);
            }
        }

        List<String> withWorkflow = perTarget.entrySet().stream()
                .filter(t -> t.getValue().keySet().stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).endsWith(".xaml")))
                .map(Map.Entry::getKey).toList();

        if (withWorkflow.isEmpty()) {
            if (hasLibrary && libraryTag) {
                throw ApiException.badRequest("'" + shownName + "' adalah library OpenStudio, bukan paket proses."
                        + " Unggah lewat Tenant › Library.").withCode(IS_LIBRARY);
            }
            if (hasLibrary) {
                throw ApiException.badRequest("'" + shownName + "' adalah paket activity (pustaka .dll), bukan paket"
                        + " proses. Pasang lewat OpenStudio › Kelola Paket.").withCode(NOT_PROCESS);
            }
            throw ApiException.badRequest("Paket '" + shownName + "' tidak berisi workflow (.xaml) di lib/net45/.")
                    .withCode(NO_WORKFLOW);
        }

        String target = withWorkflow.stream().filter(t -> t.equalsIgnoreCase("net45")).findFirst()
                .orElse(withWorkflow.size() == 1 ? withWorkflow.getFirst() : null);
        if (target == null) {
            throw ApiException.badRequest("Paket '" + shownName + "' berisi workflow untuk beberapa target ("
                    + String.join(", ", withWorkflow.stream().map(t -> "lib/" + t + "/").toList())
                    + "); tidak jelas mana yang dijalankan.").withCode(NO_WORKFLOW);
        }

        return perTarget.get(target);
    }

    /** Percent-decoding per bagian, seperti Uri.UnescapeDataString: "+" tetap "+". */
    static String unescapePart(String path) {
        if (path.indexOf('%') < 0) return path;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length() && isHex(path.charAt(i + 1)) && isHex(path.charAt(i + 2))) {
                bytes.write(Integer.parseInt(path.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(utf8, 0, utf8.length);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    // -------------------------------------------------------------------- isi

    static byte[] readBytes(ZipFile zip, ZipEntry entry, String path, String shownName, long limit)
            throws IOException {
        if (entry.getSize() > limit) {
            throw ApiException.badRequest("Berkas " + path + " di '" + shownName + "' terlalu besar untuk diperiksa.")
                    .withCode(TOO_LARGE);
        }

        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, limit, shownName);
            return out.toByteArray();
        } catch (java.util.zip.ZipException e) {
            throw corrupt(shownName, path + ": " + e.getMessage());
        }
    }

    private static String readText(ZipFile zip, ZipEntry entry, String path, String shownName) throws IOException {
        return new String(readBytes(zip, entry, path, shownName, MAX_TEXT_FILE_BYTES), StandardCharsets.UTF_8)
                .replaceFirst("^﻿", "");
    }

    /** Salin dengan batas: ukuran di direktori zip bisa bohong, isi yang mengembang tidak. */
    private static long copy(InputStream in, OutputStream out, long limit, String shownName) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > limit) throw tooLargeUncompressed(shownName);
            out.write(buffer, 0, n);
        }
        return total;
    }

    /** Zip proyek baru: berkas proyek di akarnya, waktu ubahnya dipertahankan. */
    private static byte[] repack(ZipFile zip, Map<String, ZipEntry> project, String shownName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long remaining = MAX_UNCOMPRESSED_BYTES;

        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, ZipEntry> e : project.entrySet()) {
                ZipEntry copy = new ZipEntry(e.getKey());
                if (e.getValue().getTime() > 0) copy.setTime(e.getValue().getTime());
                out.putNextEntry(copy);
                try (InputStream in = zip.getInputStream(e.getValue())) {
                    remaining -= copy(in, out, remaining, shownName);
                } catch (java.util.zip.ZipException ex) {
                    throw corrupt(shownName, e.getKey() + ": " + ex.getMessage());
                }
                out.closeEntry();
            }
        }

        return bytes.toByteArray();
    }

    // -------------------------------------------------------------------- XML

    private record Xaml(String rootElement, Set<String> namespaces) {
    }

    /**
     * Pembaca XML tanpa DTD dan tanpa entitas luar: isi paket datang dari
     * luar, dan XAML yang sah tidak pernah membutuhkan keduanya.
     */
    static XMLInputFactory xmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    private static String prefixed(XMLStreamReader reader) {
        String prefix = reader.getPrefix();
        return (prefix == null || prefix.isEmpty() ? "" : prefix + ":") + reader.getLocalName();
    }

    private static Xaml readXaml(ZipFile zip, ZipEntry entry, String path, String shownName) throws IOException {
        byte[] content = readBytes(zip, entry, path, shownName, MAX_TEXT_FILE_BYTES);
        String root = null;
        Set<String> namespaces = new LinkedHashSet<>();

        XMLStreamReader reader = null;
        try {
            reader = xmlFactory().createXMLStreamReader(new ByteArrayInputStream(content));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DOCTYPE tidak diizinkan");
                if (event != XMLStreamConstants.START_ELEMENT) continue;

                if (root == null) {
                    root = WORKFLOW_NAMESPACE.equals(reader.getNamespaceURI()) ? reader.getLocalName()
                            : prefixed(reader) + (reader.getNamespaceURI() == null ? "" : " xmlns=" + reader.getNamespaceURI());
                }
                for (int i = 0; i < reader.getNamespaceCount(); i++) {
                    String uri = reader.getNamespaceURI(i);
                    if (uri != null) namespaces.add(uri);
                }
            }
        } catch (XMLStreamException e) {
            throw ApiException.badRequest("Workflow " + path + " di '" + shownName + "' bukan XAML yang utuh: "
                    + firstLine(e.getMessage())).withCode(INVALID_XAML);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Sudah selesai dibaca.
                }
            }
        }

        if (root == null) {
            throw ApiException.badRequest("Workflow " + path + " di '" + shownName + "' kosong.").withCode(INVALID_XAML);
        }

        return new Xaml(root, namespaces);
    }

    /** id, version, description, tags dari &lt;metadata&gt; .nuspec, apa pun namespace-nya. */
    private static Map<String, String> readNuspec(ZipFile zip, ZipEntry entry, String shownName) throws IOException {
        byte[] content = readBytes(zip, entry, entry.getName(), shownName, MAX_TEXT_FILE_BYTES);
        Map<String, String> values = new LinkedHashMap<>();

        XMLStreamReader reader = null;
        try {
            reader = xmlFactory().createXMLStreamReader(new ByteArrayInputStream(content));
            List<String> path = new ArrayList<>();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DOCTYPE tidak diizinkan");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    path.add(reader.getLocalName());
                    if (path.size() == 3 && path.get(1).equals("metadata")
                            && Set.of("id", "version", "description", "tags").contains(path.get(2))) {
                        values.put(path.get(2), reader.getElementText().trim());
                        path.removeLast();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    path.removeLast();
                }
            }
        } catch (XMLStreamException e) {
            throw corrupt(shownName, ".nuspec tidak terbaca: " + firstLine(e.getMessage()));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Sudah selesai dibaca.
                }
            }
        }

        return values;
    }

    // -------------------------------------------------------------------- titik masuk

    /**
     * Urutan robot (EntryPointResolver): "main"/"entryPoint" di project.json
     * kalau berkasnya ada, lalu Main.xaml, lalu satu-satunya .xaml di akar.
     * Banyak .xaml tanpa petunjuk TIDAK ditebak — robot juga tidak.
     */
    private static String entryPoint(Map<String, ZipEntry> project, JsonNode projectJson, List<String> rootXaml,
                                     List<String> warnings, String shownName) {
        String declared = firstText(text(projectJson, "main"), text(projectJson, "entryPoint"));
        if (declared != null) {
            String cleaned = declared.replace('\\', '/').replaceFirst("^\\./", "");
            if (project.containsKey(cleaned)) {
                String actual = project.keySet().stream().filter(cleaned::equalsIgnoreCase).findFirst().orElse(cleaned);
                if (!actual.toLowerCase(Locale.ROOT).endsWith(".xaml")) {
                    throw ApiException.badRequest("Titik masuk '" + declared + "' di project.json bukan berkas .xaml.")
                            .withCode(ENTRY_POINT);
                }
                return actual;
            }
            warnings.add("project.json menyebut titik masuk '" + declared + "' yang tidak ada di paket; robot memakai"
                    + " urutan berikutnya (Main.xaml, atau satu-satunya .xaml).");
        }

        String main = rootXaml.stream().filter(p -> p.equalsIgnoreCase(MAIN_XAML)).findFirst().orElse(null);
        if (main != null) return main;
        if (rootXaml.size() == 1) return rootXaml.getFirst();

        if (rootXaml.isEmpty()) {
            throw ApiException.badRequest("Paket '" + shownName + "' tidak berisi berkas .xaml di akar proyeknya.")
                    .withCode(NO_WORKFLOW);
        }
        throw ApiException.badRequest("Paket '" + shownName + "' berisi " + rootXaml.size() + " berkas .xaml tanpa"
                + " titik masuk yang jelas. Isi \"main\" di project.json atau beri nama Main.xaml.").withCode(ENTRY_POINT);
    }

    // -------------------------------------------------------------------- alat

    /**
     * "libraries" project.json (V21): daftar Id, versinya tetap di
     * "dependencies". Yang bukan teks diabaikan; Id ganda (beda huruf) sekali.
     */
    private static List<String> declaredLibraries(JsonNode projectJson) {
        JsonNode node = projectJson == null ? null : projectJson.get("libraries");
        if (node == null || !node.isArray()) return List.of();

        List<String> result = new ArrayList<>();
        for (JsonNode id : node) {
            if (!id.isTextual() || id.asText().isBlank()) continue;
            String trimmed = id.asText().trim();
            if (result.stream().noneMatch(trimmed::equalsIgnoreCase)) result.add(trimmed);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> dependencies(JsonNode projectJson) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode node = projectJson == null ? null : projectJson.get("dependencies");
        if (node == null || !node.isObject()) return result;

        node.properties().forEach(e -> result.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : ""));
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static String firstText(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    static String firstLine(String message) {
        if (message == null) return "";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    static ApiException corrupt(String shownName, String detail) {
        return ApiException.badRequest("Berkas '" + shownName + "' rusak atau bukan paket yang utuh"
                + (detail == null || detail.isBlank() ? "." : " (" + detail + ").")).withCode(CORRUPT);
    }

    private static ApiException tooLargeUncompressed(String shownName) {
        return ApiException.badRequest("Isi paket '" + shownName + "' lebih dari "
                + MAX_UNCOMPRESSED_BYTES / (1024 * 1024) + " MB sesudah dibuka.").withCode(TOO_LARGE);
    }
}
