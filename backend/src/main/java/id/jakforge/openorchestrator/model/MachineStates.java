package id.jakforge.openorchestrator.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Keadaan mesin yang diatur admin (kolom {@code machines.state}, V12), dan
 * status yang tampil di layar — gabungan keadaan itu dengan denyutnya.
 *
 * <p>Keadaan: {@link #ACTIVE} bekerja seperti biasa; {@link #MAINTENANCE}
 * sementara tidak mengambil job baru; {@link #DISABLED} tidak dipakai sama
 * sekali dan tidak ditawarkan saat mendaftarkan mesin ke folder. Job yang
 * sedang berjalan tidak dihentikan oleh keduanya.
 *
 * <p>Status: {@link #DISABLED_STATUS} dan {@link #MAINTENANCE_STATUS} dari
 * keadaannya; selain itu {@link #ONLINE} (agent atau robot v1 di mesin itu
 * berdenyut baru-baru ini), {@link #DISCONNECTED} (pernah berdenyut, sekarang
 * tidak), atau {@link #OFFLINE} (belum pernah tersambung). Hanya mesin ONLINE
 * yang bisa dipilih di Start Job.
 */
public final class MachineStates {

    public static final String ACTIVE = "Active";
    public static final String MAINTENANCE = "Maintenance";
    public static final String DISABLED = "Disabled";

    public static final List<String> ALL = List.of(ACTIVE, MAINTENANCE, DISABLED);

    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";
    public static final String DISCONNECTED = "DISCONNECTED";
    public static final String MAINTENANCE_STATUS = "MAINTENANCE";
    public static final String DISABLED_STATUS = "DISABLED";

    private MachineStates() {
    }

    /** "maintenance" menjadi "Maintenance"; kosong untuk teks kosong dan keadaan yang tidak dikenal. */
    public static Optional<String> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();

        String wanted = text.trim().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(state -> state.toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    /**
     * Status mesin sebagai ekspresi SQL atas baris {@code machines} beralias
     * {@code alias}. Batas waktunya angka dari konfigurasi, bukan masukan
     * orang, jadi boleh ditulis langsung ke kalimatnya.
     *
     * @param agentOnlineSeconds denyut agent selama ini masih berarti tersambung
     * @param robotOnlineSeconds denyut robot v1 selama ini masih berarti tersambung
     */
    public static String statusSql(String alias, long agentOnlineSeconds, long robotOnlineSeconds) {
        String v1Robots = """
                SELECT 1 FROM robots sr
                 WHERE sr.tenant_id = %1$s.tenant_id AND sr.machine_id IS NULL AND sr.machine_name = %1$s.name
                   AND sr.last_heartbeat_at IS NOT NULL""".formatted(alias);

        return """
                (CASE
                   WHEN %1$s.state = 'Disabled' THEN 'DISABLED'
                   WHEN %1$s.state = 'Maintenance' THEN 'MAINTENANCE'
                   WHEN (%1$s.last_agent_heartbeat_at IS NOT NULL
                         AND now() - %1$s.last_agent_heartbeat_at <= make_interval(secs => %2$d))
                     OR EXISTS (%4$s AND now() - sr.last_heartbeat_at <= make_interval(secs => %3$d))
                     THEN 'ONLINE'
                   WHEN %1$s.last_agent_heartbeat_at IS NOT NULL OR EXISTS (%4$s) THEN 'DISCONNECTED'
                   ELSE 'OFFLINE'
                 END)""".formatted(alias, agentOnlineSeconds, robotOnlineSeconds, v1Robots);
    }

    /**
     * Nama komputer mesin itu: yang dilaporkan Robot Agent saat masuk, atau —
     * untuk mesin robot v1, yang denyutnya menyebut nama komputer sebagai nama
     * mesin — namanya sendiri. Null kalau belum pernah ada yang tersambung.
     */
    public static String hostnameSql(String alias) {
        return """
                COALESCE(%1$s.agent_host_name,
                         CASE WHEN EXISTS (SELECT 1 FROM robots hr
                                            WHERE hr.tenant_id = %1$s.tenant_id AND hr.machine_id IS NULL
                                              AND hr.machine_name = %1$s.name)
                              THEN %1$s.name END)""".formatted(alias);
    }
}
