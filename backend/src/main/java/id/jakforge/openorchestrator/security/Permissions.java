package id.jakforge.openorchestrator.security;

/**
 * Nama sumber, tindakan, dan izin yang disebut langsung oleh kode.
 *
 * <p>Izin berbentuk {@code sumber.tindakan} — "processes.read", "folders.update".
 * Konstanta di sini dipakai layanan yang memeriksa izin di tengah jalan; tabel
 * izin per endpoint ada di {@link PermissionInterceptor}, dan seluruh katalog
 * yang bisa dipilih di layar Peran ada di {@link PermissionCatalog}.
 */
public final class Permissions {

    // --- sumber ---
    public static final String PROCESSES = "processes";
    public static final String JOBS = "jobs";
    public static final String TRIGGERS = "triggers";
    public static final String PACKAGES = "packages";
    public static final String QUEUES = "queues";
    public static final String ASSETS = "assets";
    public static final String BUCKETS = "buckets";
    public static final String ROBOTS = "robots";
    public static final String LOGS = "logs";
    public static final String FOLDERS = "folders";
    public static final String MACHINES = "machines";
    public static final String ENVIRONMENTS = "environments";
    public static final String USERS = "users";
    public static final String ROLES = "roles";
    public static final String ALERTS = "alerts";
    public static final String AUDIT = "audit";
    public static final String SETTINGS = "settings";

    // --- tindakan ---
    public static final String READ = "read";
    public static final String CREATE = "create";
    public static final String UPDATE = "update";
    public static final String DELETE = "delete";

    /** Pola yang mencakup segalanya — milik peran Administrator. */
    public static final String ALL = "*";

    // --- izin yang disebut langsung oleh layanan ---
    public static final String FOLDERS_READ = of(FOLDERS, READ);
    public static final String FOLDERS_CREATE = of(FOLDERS, CREATE);
    public static final String FOLDERS_UPDATE = of(FOLDERS, UPDATE);
    public static final String FOLDERS_DELETE = of(FOLDERS, DELETE);
    public static final String USERS_CREATE = of(USERS, CREATE);
    public static final String USERS_UPDATE = of(USERS, UPDATE);

    private Permissions() {
    }

    /** "processes" + "read" menjadi "processes.read". */
    public static String of(String resource, String action) {
        return resource + "." + action;
    }
}
