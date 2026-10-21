package id.jakforge.openorchestrator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Library mana yang diberikan ke sebuah job (V20). */
class LibraryResolverTest {

    private final List<LibraryResolver.Available> feed = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();

    private void add(String name, String version, Map<String, String> dependencies) {
        feed.add(new LibraryResolver.Available(name, version, "sha-" + name + "-" + version, 100, dependencies));
    }

    private LibraryResolver.Resolution resolve(Map<String, String> dependencies) {
        return LibraryResolver.resolve(dependencies, lowerName -> {
            asked.add(lowerName);
            return feed.stream().filter(a -> a.name().toLowerCase(Locale.ROOT).equals(lowerName)).toList();
        });
    }

    private static List<String> names(LibraryResolver.Resolution r) {
        return r.libraries().stream().map(l -> l.name() + " " + l.version()).toList();
    }

    @Test
    @DisplayName("versi yang diminta: batas bawah inklusif, persis — seperti OpenStudio memulihkan dependensi")
    void requiredVersion() {
        assertEquals("1.2.0", LibraryResolver.requiredVersion("1.2.0"));
        assertEquals("1.2.0", LibraryResolver.requiredVersion(" [1.2.0] "));
        assertEquals("1.2.0", LibraryResolver.requiredVersion("[1.2.0, )"));
        assertEquals("1.2.0", LibraryResolver.requiredVersion("[1.2.0,2.0.0)"));
        assertEquals("1.2.0-beta.1", LibraryResolver.requiredVersion("1.2.0-beta.1"));
        assertNull(LibraryResolver.requiredVersion("(1.2.0, )"), "batas bawah eksklusif: tidak ada versi persis");
        assertNull(LibraryResolver.requiredVersion("(, 2.0.0]"));
        assertNull(LibraryResolver.requiredVersion("(1.2.0)"));
        assertNull(LibraryResolver.requiredVersion("*"));
        assertNull(LibraryResolver.requiredVersion(""));
        assertNull(LibraryResolver.requiredVersion(null));
    }

    @Test
    @DisplayName("hanya Id yang ada di feed yang dianggap library; activity diabaikan")
    void onlyFeedEntriesAreLibraries() {
        add("Tagihan.Umum", "1.2.0", Map.of());
        add("Tagihan.Umum", "1.3.0", Map.of());

        LibraryResolver.Resolution r = resolve(Map.of("tagihan.umum", "1.2.0", "OpenStudio.Mail.Activities", "2.0.1"));

        assertTrue(r.ok(), r.problems().toString());
        assertEquals(List.of("Tagihan.Umum 1.2.0"), names(r), "versi persis, bukan yang tertinggi; Id seperti di feed");
        assertEquals("sha-Tagihan.Umum-1.2.0", r.libraries().getFirst().sha256());
    }

    @Test
    @DisplayName("1.2 dan 1.2.0 versi yang sama")
    void equivalentVersions() {
        add("A", "1.2", Map.of());

        assertEquals(List.of("A 1.2"), names(resolve(Map.of("A", "1.2.0"))));
    }

    @Test
    @DisplayName("versi yang tidak ada di feed: masalah, dengan versi yang ada")
    void missingVersion() {
        add("Tagihan.Umum", "1.3.0", Map.of());

        LibraryResolver.Resolution r = resolve(Map.of("Tagihan.Umum", "1.2.0"));

        assertEquals(1, r.problems().size());
        assertTrue(r.problems().getFirst().contains("Tagihan.Umum versi 1.2.0 tidak ada di feed library"), r.problems().toString());
        assertTrue(r.problems().getFirst().contains("1.3.0"), r.problems().toString());
    }

    @Test
    @DisplayName("versi yang tidak bisa ditentukan untuk Id library: masalah")
    void undeterminedVersion() {
        add("A", "1.0.0", Map.of());

        assertTrue(resolve(Map.of("A", "(1.0.0,)")).problems().getFirst().contains("tidak bisa ditentukan"));
    }

    @Test
    @DisplayName("dependensi library lewat .nuspec ikut diberikan, bertingkat")
    void transitive() {
        add("Tagihan.Umum", "1.2.0", Map.of("Tagihan.Dasar", "[1.0.0]", "OpenStudio.Excel.Activities", "1.0.0"));
        add("Tagihan.Dasar", "1.0.0", Map.of("Format.Angka", "2.0.0"));
        add("Format.Angka", "2.0.0", Map.of());

        LibraryResolver.Resolution r = resolve(Map.of("Tagihan.Umum", "1.2.0"));

        assertTrue(r.ok(), r.problems().toString());
        assertEquals(List.of("Tagihan.Umum 1.2.0", "Tagihan.Dasar 1.0.0", "Format.Angka 2.0.0"), names(r));
    }

    @Test
    @DisplayName("library turunan yang tidak ada: masalah menyebut siapa yang membutuhkannya")
    void transitiveMissing() {
        add("Tagihan.Umum", "1.2.0", Map.of("Tagihan.Dasar", "1.0.0"));
        add("Tagihan.Dasar", "0.9.0", Map.of());

        LibraryResolver.Resolution r = resolve(Map.of("Tagihan.Umum", "1.2.0"));

        assertTrue(r.problems().getFirst().contains("dibutuhkan library Tagihan.Umum 1.2.0"), r.problems().toString());
    }

    @Test
    @DisplayName("satu library dalam dua versi: masalah; dalam versi yang sama: sekali saja")
    void versionConflict() {
        add("A", "1.0.0", Map.of("C", "1.0.0"));
        add("B", "1.0.0", Map.of("C", "2.0.0"));
        add("C", "1.0.0", Map.of());
        add("C", "2.0.0", Map.of());

        Map<String, String> both = new LinkedHashMap<>();
        both.put("A", "1.0.0");
        both.put("B", "1.0.0");
        LibraryResolver.Resolution conflict = resolve(both);
        assertEquals(1, conflict.problems().size(), conflict.problems().toString());
        assertTrue(conflict.problems().getFirst().contains("dua versi"), conflict.problems().toString());

        feed.removeIf(a -> a.name().equals("B"));
        add("B", "1.0.0", Map.of("C", "1.0"));
        LibraryResolver.Resolution same = resolve(both);
        assertTrue(same.ok(), same.problems().toString());
        assertEquals(List.of("A 1.0.0", "B 1.0.0", "C 1.0.0"), names(same));
    }

    @Test
    @DisplayName("lingkaran dependensi berhenti; satu kueri feed per Id")
    void cycleStops() {
        add("A", "1.0.0", Map.of("B", "1.0.0"));
        add("B", "1.0.0", Map.of("A", "1.0.0"));

        LibraryResolver.Resolution r = resolve(Map.of("A", "1.0.0"));

        assertTrue(r.ok(), r.problems().toString());
        assertEquals(List.of("A 1.0.0", "B 1.0.0"), names(r));
        assertEquals(List.of("a", "b"), asked);
    }

    @Test
    @DisplayName("tanpa dependensi: tidak ada library, tanpa kueri")
    void noDependencies() {
        assertTrue(resolve(Map.of()).libraries().isEmpty());
        assertTrue(resolve(null).ok());
        assertTrue(asked.isEmpty());
    }
}
