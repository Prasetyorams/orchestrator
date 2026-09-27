package id.jakforge.openorchestrator.security;

import id.jakforge.openorchestrator.common.ApiException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static id.jakforge.openorchestrator.security.Permissions.ALERTS;
import static id.jakforge.openorchestrator.security.Permissions.ALL;
import static id.jakforge.openorchestrator.security.Permissions.ASSETS;
import static id.jakforge.openorchestrator.security.Permissions.AUDIT;
import static id.jakforge.openorchestrator.security.Permissions.BUCKETS;
import static id.jakforge.openorchestrator.security.Permissions.CREATE;
import static id.jakforge.openorchestrator.security.Permissions.DELETE;
import static id.jakforge.openorchestrator.security.Permissions.ENVIRONMENTS;
import static id.jakforge.openorchestrator.security.Permissions.FOLDERS;
import static id.jakforge.openorchestrator.security.Permissions.JOBS;
import static id.jakforge.openorchestrator.security.Permissions.LOGS;
import static id.jakforge.openorchestrator.security.Permissions.MACHINES;
import static id.jakforge.openorchestrator.security.Permissions.PACKAGES;
import static id.jakforge.openorchestrator.security.Permissions.PROCESSES;
import static id.jakforge.openorchestrator.security.Permissions.QUEUES;
import static id.jakforge.openorchestrator.security.Permissions.READ;
import static id.jakforge.openorchestrator.security.Permissions.ROBOTS;
import static id.jakforge.openorchestrator.security.Permissions.ROLES;
import static id.jakforge.openorchestrator.security.Permissions.SETTINGS;
import static id.jakforge.openorchestrator.security.Permissions.TRIGGERS;
import static id.jakforge.openorchestrator.security.Permissions.UPDATE;
import static id.jakforge.openorchestrator.security.Permissions.USERS;

/**
 * Katalog izin dan aturan pola peran.
 *
 * <p>Peran menyimpan POLA, dipisah koma, di kolom {@code roles.permissions}:
 *
 * <ul>
 *   <li>{@code *} — semuanya (Administrator);</li>
 *   <li>{@code processes.*} — semua tindakan atas proses;</li>
 *   <li>{@code *.read} — membaca apa pun (Auditor);</li>
 *   <li>{@code processes.read} — satu tindakan.</li>
 * </ul>
 *
 * <p>Seluruhnya fungsi murni tanpa basis data; izin seseorang saat ini dibaca
 * oleh {@link PermissionService}.
 */
public final class PermissionCatalog {

    /** Satu baris matriks izin: sumber dan tindakan yang berlaku untuknya. */
    public record Resource(String key, List<String> actions) {
    }

    private static final List<String> CRUD = List.of(READ, CREATE, UPDATE, DELETE);

    private static final String WILDCARD_ACTION = ".*";
    private static final String WILDCARD_RESOURCE = "*.";

    /**
     * Semua izin yang dikenal, dalam urutan baris matriks di layar Peran.
     *
     * <p>Tindakan yang tidak punya endpoint tidak ditawarkan: mesin dan
     * lingkungan hanya bisa dibuat dan dihapus, jejak audit hanya dibaca.
     * Kotak centang yang tidak mengubah apa pun hanya menyesatkan orang yang
     * menyusun perannya.
     *
     * <p>{@code robots.update} adalah izin DENYUT robot: akun yang dipakai
     * JakRunner memerlukannya, bersama jobs.update, logs.create, assets.read,
     * dan queues.update — lihat peran Robot di V6.
     */
    public static final List<Resource> RESOURCES = List.of(
            new Resource(PROCESSES, CRUD),
            new Resource(JOBS, CRUD),
            new Resource(TRIGGERS, CRUD),
            new Resource(PACKAGES, CRUD),
            new Resource(QUEUES, CRUD),
            new Resource(ASSETS, CRUD),
            new Resource(BUCKETS, CRUD),
            new Resource(ROBOTS, CRUD),
            new Resource(LOGS, List.of(READ, CREATE, DELETE)),
            new Resource(FOLDERS, CRUD),
            new Resource(MACHINES, List.of(READ, CREATE, DELETE)),
            new Resource(ENVIRONMENTS, List.of(READ, CREATE, DELETE)),
            new Resource(USERS, CRUD),
            new Resource(ROLES, CRUD),
            new Resource(ALERTS, List.of(READ, UPDATE)),
            new Resource(AUDIT, List.of(READ)),
            new Resource(SETTINGS, List.of(READ)));

    private PermissionCatalog() {
    }

    /** Apakah salah satu pola mencakup izin itu. */
    public static boolean matches(Collection<String> patterns, String permission) {
        int dot = permission.indexOf('.');
        String resource = permission.substring(0, dot);
        String action = permission.substring(dot + 1);

        for (String pattern : patterns) {
            if (pattern.equals(ALL)
                    || pattern.equals(permission)
                    || pattern.equals(resource + WILDCARD_ACTION)
                    || pattern.equals(WILDCARD_RESOURCE + action)) {
                return true;
            }
        }

        return false;
    }

    /** "a, b,c" menjadi {a, b, c}; kosong dan null menjadi himpunan kosong. */
    public static Set<String> parsePatterns(String text) {
        Set<String> patterns = new LinkedHashSet<>();
        if (text == null) return patterns;

        for (String part : text.split(",")) {
            String pattern = part.trim();
            if (!pattern.isEmpty()) patterns.add(pattern);
        }

        return patterns;
    }

    /** Setiap izin di katalog yang dicakup pola itu. */
    public static Set<String> expand(Collection<String> patterns) {
        Set<String> permissions = new LinkedHashSet<>();

        for (Resource resource : RESOURCES) {
            for (String action : resource.actions()) {
                String permission = Permissions.of(resource.key(), action);
                if (matches(patterns, permission)) permissions.add(permission);
            }
        }

        return permissions;
    }

    /**
     * Izin dari layar Peran, dalam bentuk yang disimpan: urutan katalog, dan
     * {@code sumber.*} untuk sumber yang semua tindakannya dipilih — supaya
     * dua peran yang sama tertulis sama, dan tindakan baru di masa depan ikut
     * terbawa ke peran yang memang sudah memegang seluruh sumber itu.
     *
     * <p>Yang tidak dikenal DITOLAK, bukan dibuang diam-diam: salah ketik di
     * "procesess.read" berarti peran yang disimpan tidak bisa apa-apa, dan
     * penyusunnya tidak akan pernah tahu sebabnya.
     */
    public static List<String> normalize(Collection<String> selection) {
        Set<String> selected = new LinkedHashSet<>();

        for (String item : selection) {
            String permission = item == null ? "" : item.trim();
            if (permission.isEmpty()) continue;

            if (!isKnown(permission)) {
                throw ApiException.badRequest("Izin tidak dikenal: '" + permission + "'.");
            }

            selected.addAll(expand(List.of(permission)));
        }

        List<String> normalized = new ArrayList<>();

        for (Resource resource : RESOURCES) {
            List<String> chosenActions = resource.actions().stream()
                    .filter(action -> selected.contains(Permissions.of(resource.key(), action)))
                    .toList();

            if (chosenActions.size() == resource.actions().size()) {
                normalized.add(resource.key() + WILDCARD_ACTION);
            } else {
                for (String action : chosenActions) normalized.add(Permissions.of(resource.key(), action));
            }
        }

        return normalized;
    }

    /** "processes.read" atau "processes.*", dengan sumber dan tindakan dari katalog. */
    static boolean isKnown(String permission) {
        int dot = permission.indexOf('.');
        if (dot <= 0) return false;

        String resourceKey = permission.substring(0, dot);
        String action = permission.substring(dot + 1);

        for (Resource resource : RESOURCES) {
            if (resource.key().equals(resourceKey)) return action.equals(ALL) || resource.actions().contains(action);
        }

        return false;
    }

    /**
     * Izin pertama dari {@code requested} yang TIDAK dicakup {@code granted},
     * atau null kalau semuanya tercakup.
     *
     * <p>Dipakai supaya tidak ada yang bisa memberi — lewat peran baru, atau
     * dengan memasang peran pada seseorang — izin yang ia sendiri tidak punya.
     * Tanpa itu, siapa pun yang boleh menyunting peran bisa menjadikan dirinya
     * Administrator dalam dua klik.
     */
    public static String firstUncovered(Collection<String> granted, Collection<String> requested) {
        for (String permission : expand(requested)) {
            if (!matches(granted, permission)) return permission;
        }

        return null;
    }
}
