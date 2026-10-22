package id.jakforge.openorchestrator.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Library mana yang dibutuhkan sebuah paket proses (V20).
 *
 * <p>Dependensi project.json yang Id-nya ADA di feed library adalah library;
 * yang tidak ada di feed dianggap paket activity yang dipasang di mesin robot
 * (aturan lama, tidak berubah). Library juga boleh bergantung pada library
 * lain lewat .nuspec-nya — yang itu ikut diberikan.
 *
 * <p>Studio yang lebih baru menandai dependensi library di "libraries"
 * project.json (V21). Id yang ditandai begitu PASTI library: kalau tidak ada di
 * feed, itu masalah — bukan activity yang kebetulan tidak dikenal.
 *
 * <p>Versinya sama dengan cara OpenStudio memulihkan dependensi proyek
 * (Project.InstallDependencies): batas bawah rentang versi NuGet, kalau
 * inklusif, dan PERSIS versi itu. "1.2.0", "[1.2.0]", dan "[1.2.0, )" semuanya
 * berarti 1.2.0. Robot menjalankan yang sama dengan yang dicoba pembuatnya di
 * Studio, bukan versi lain yang kebetulan lebih baru di feed.
 */
final class LibraryResolver {

    /** Batas jumlah library per job: rantai dependensi yang tak masuk akal dihentikan, bukan diikuti selamanya. */
    static final int MAX_LIBRARIES = 100;

    /** Satu versi library di feed. */
    record Available(String name, String version, String sha256, long sizeBytes, Map<String, String> dependencies) {
    }

    /**
     * @param libraries library yang dibutuhkan, urut sesuai ditemukannya
     * @param problems  yang membuat job tidak bisa dijalankan; kosong kalau semuanya ada
     */
    record Resolution(List<Available> libraries, List<String> problems) {

        boolean ok() {
            return problems.isEmpty();
        }

        static Resolution none() {
            return new Resolution(List.of(), List.of());
        }
    }

    private record Need(String name, String spec, String requiredBy) {
    }

    private LibraryResolver() {
    }

    /**
     * Versi yang diminta sebuah dependensi, atau null kalau tidak bisa
     * ditentukan (rentang tanpa batas bawah inklusif, atau bukan versi).
     */
    static String requiredVersion(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return null;

        String version;
        if (s.startsWith("[") || s.startsWith("(")) {
            if (s.length() < 3 || !(s.endsWith("]") || s.endsWith(")"))) return null;
            if (s.startsWith("(")) return null;

            String inner = s.substring(1, s.length() - 1);
            int comma = inner.indexOf(',');
            if (comma < 0) {
                // [1.2.0] — persis; (1.2.0) bukan rentang yang sah.
                if (!s.endsWith("]")) return null;
                version = inner.trim();
            } else {
                version = inner.substring(0, comma).trim();
            }
        } else {
            version = s;
        }

        return PackageNaming.isValidVersion(version) ? version : null;
    }

    /**
     * @param dependencies dependensi project.json paket proses, Id → versi
     * @param feed         versi yang ada di feed untuk satu Id (huruf kecil); kosong kalau Id itu bukan library
     */
    static Resolution resolve(Map<String, String> dependencies, Function<String, List<Available>> feed) {
        return resolve(dependencies, List.of(), feed);
    }

    /** @param declared Id di "libraries" project.json: library walau belum ada di feed */
    static Resolution resolve(Map<String, String> dependencies, Collection<String> declared,
                              Function<String, List<Available>> feed) {
        Map<String, String> safeDependencies = dependencies == null ? Map.of() : dependencies;
        Collection<String> safeDeclared = declared == null ? List.of() : declared;
        if (safeDependencies.isEmpty() && safeDeclared.isEmpty()) return Resolution.none();

        Map<String, List<Available>> versions = new HashMap<>();
        Function<String, List<Available>> versionsOf =
                name -> versions.computeIfAbsent(name.toLowerCase(Locale.ROOT), feed);

        Map<String, Available> chosen = new LinkedHashMap<>();
        Map<String, String> chosenFor = new HashMap<>();
        List<String> problems = new ArrayList<>();

        Set<String> declaredKeys = new HashSet<>();
        for (String id : safeDeclared) {
            declaredKeys.add(id.toLowerCase(Locale.ROOT));
            if (safeDependencies.keySet().stream().noneMatch(id::equalsIgnoreCase)) {
                problems.add("Library " + id + " ditandai di \"libraries\" project.json, tetapi versinya tidak disebut"
                        + " di \"dependencies\".");
            }
        }

        Deque<Need> queue = new ArrayDeque<>();
        safeDependencies.forEach((name, spec) -> queue.add(new Need(name, spec, null)));

        while (!queue.isEmpty()) {
            Need need = queue.poll();
            String key = need.name().toLowerCase(Locale.ROOT);
            List<Available> available = versionsOf.apply(need.name());
            if (available.isEmpty()) {
                // Bukan library (activity di mesin robot) — kecuali Studio menandainya library.
                if (need.requiredBy() == null && declaredKeys.contains(key)) {
                    String required = requiredVersion(need.spec());
                    problems.add("Library " + need.name() + (required == null ? "" : " versi " + required)
                            + " belum ada di feed library.");
                }
                continue;
            }

            String shownName = available.getFirst().name();
            String by = need.requiredBy() == null ? "" : " (dibutuhkan " + need.requiredBy() + ")";

            String required = requiredVersion(need.spec());
            if (required == null) {
                problems.add("Versi library " + shownName + " tidak bisa ditentukan dari '" + need.spec() + "'" + by
                        + ". Pakai versi persis, mis. 1.0.0.");
                continue;
            }

            Available match = available.stream()
                    .filter(a -> PackageNaming.compare(a.version(), required) == 0).findFirst().orElse(null);
            if (match == null) {
                problems.add("Library " + shownName + " versi " + required + " tidak ada di feed library" + by
                        + ". Versi yang ada: " + String.join(", ", available.stream().map(Available::version).toList())
                        + ".");
                continue;
            }

            Available already = chosen.get(key);
            if (already != null) {
                if (PackageNaming.compare(already.version(), match.version()) != 0) {
                    problems.add("Library " + shownName + " dibutuhkan dalam dua versi: " + already.version()
                            + " (" + chosenFor.get(key) + ") dan " + match.version()
                            + (need.requiredBy() == null ? " (paket proses)" : " (" + need.requiredBy() + ")")
                            + ". Robot hanya bisa memasang satu.");
                }
                continue;
            }

            if (chosen.size() >= MAX_LIBRARIES) {
                problems.add("Paket ini membutuhkan lebih dari " + MAX_LIBRARIES + " library.");
                break;
            }

            chosen.put(key, match);
            chosenFor.put(key, need.requiredBy() == null ? "paket proses" : need.requiredBy());

            String parent = "library " + match.name() + " " + match.version();
            match.dependencies().forEach((name, spec) -> queue.add(new Need(name, spec, parent)));
        }

        return new Resolution(List.copyOf(chosen.values()), List.copyOf(problems));
    }
}
