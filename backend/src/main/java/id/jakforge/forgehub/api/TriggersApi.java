package id.jakforge.forgehub.api;

import id.jakforge.forgehub.data.Db;
import id.jakforge.forgehub.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pemicu terjadwal.
 *
 * <p>Dua cara menjadwalkan, dan keduanya ada karena masing-masing tidak bisa
 * menggantikan yang lain: SELANG ("tiap 15 menit") sederhana dan tidak menuntut
 * siapa pun menulis cron, sedangkan CRON bisa menyatakan "tiap hari kerja pukul
 * 07:00" — yang tidak bisa dinyatakan sebagai selang menit sama sekali.
 *
 * <p>Kalau keduanya diisi, cron yang menang.
 */
@RestController
@RequestMapping("/api/triggers")
public class TriggersApi {

    private final Db db;

    public TriggersApi(Db db) {
        this.db = db;
    }

    @GetMapping
    public List<Map<String, Object>> daftar() {
        return db.rows("""
                SELECT id, name, process_name, robot_name, type, cron, interval_minutes,
                       priority, timezone, runtime_type, enabled, next_run_at, last_run_at, created_at
                  FROM triggers
                 WHERE tenant_id = ?
                 ORDER BY enabled DESC, next_run_at
                """, CurrentUser.get().tenantId());
    }

    /**
     * Membuat atau memperbarui pemicu.
     *
     * <p>Nama yang sudah ada DIPERBARUI, bukan ditolak: menyunting jadwal
     * adalah hal yang paling sering dilakukan setelah membuatnya.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> simpan(@RequestBody(required = false) Map<String, Object> body) {
        String name = Badan.nama(body, "name");
        String process = Badan.nama(body, "processName");

        if (name == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nama pemicu wajib diisi."));
        }

        if (process == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "processName wajib diisi."));
        }

        int selang = Badan.bulat(body, "intervalMinutes", 60);
        String cron = Badan.nama(body, "cron");
        String zonaNama = Badan.teks(body, "timezone", "UTC");

        // Cron DIVALIDASI di sini, bukan dibiarkan sampai penjadwal.
        //
        // Ekspresi yang salah baru ketahuan pada putaran penjadwal berikutnya,
        // dan orang yang menekan "Buat pemicu" sudah pergi. Ditolak sekarang,
        // dengan penjelasan bentuk yang benar.
        if (cron != null && !Cron.isValid(cron)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Ekspresi cron tidak sah: " + cron
                            + ". Bentuknya lima ruas: menit jam tanggal bulan hari, "
                            + "mis. \"0 7 * * 1-5\" untuk tiap hari kerja pukul 07:00."));
        }

        if (cron == null && selang < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "Selang waktu minimal 1 menit."));
        }

        // Nama zona diperiksa juga. Penjadwal memang jatuh ke UTC untuk nama
        // yang tidak dikenal, tapi jatuh diam-diam berarti pemicunya berjalan
        // tujuh jam meleset tanpa ada yang tahu sebabnya.
        if (!"UTC".equals(zonaNama) && !zonaDikenal(zonaNama)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Zona waktu tidak dikenal: '" + zonaNama
                            + "'. Pakai nama IANA, mis. \"Asia/Jakarta\"."));
        }

        UUID tenantId = CurrentUser.get().tenantId();

        if (!db.exists("SELECT count(*) FROM processes WHERE tenant_id = ? AND name = ?", tenantId, process)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Proses '" + process + "' belum diterbitkan ke ForgeHub."));
        }

        boolean aktif = Badan.benar(body, "enabled", true);
        ZoneId zona = Cron.zona(zonaNama);
        OffsetDateTime berikutnya = hitungBerikutnya(cron, selang, zona);

        Object adaId = db.scalar("SELECT id FROM triggers WHERE tenant_id = ? AND name = ?", tenantId, name);

        if (adaId != null) {
            db.exec("""
                    UPDATE triggers
                       SET process_name = ?, robot_name = ?, type = ?, cron = ?,
                           interval_minutes = ?, enabled = ?, next_run_at = ?,
                           priority = ?, timezone = ?, runtime_type = ?
                     WHERE tenant_id = ? AND name = ?
                    """, process, Badan.teks(body, "robotName"),
                    Badan.teks(body, "type", cron != null ? "Cron" : "Time"), cron,
                    selang, aktif, berikutnya,
                    Badan.teks(body, "priority", "Normal"), zonaNama,
                    Badan.teks(body, "runtimeType", "Unattended"), tenantId, name);
        } else {
            db.exec("""
                    INSERT INTO triggers
                        (id, tenant_id, name, process_name, robot_name, type, cron,
                         interval_minutes, enabled, next_run_at, created_at,
                         priority, timezone, runtime_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?)
                    """, Db.newId(), tenantId, name, process, Badan.teks(body, "robotName"),
                    Badan.teks(body, "type", cron != null ? "Cron" : "Time"), cron,
                    selang, aktif, berikutnya,
                    Badan.teks(body, "priority", "Normal"), zonaNama,
                    Badan.teks(body, "runtimeType", "Unattended"));
        }

        return ResponseEntity.ok(Map.of("ok", true, "nextRunAt", String.valueOf(berikutnya)));
    }

    /**
     * Nyalakan atau matikan.
     *
     * <p>Waktu jalan berikutnya dihitung ULANG saat dinyalakan, bukan dipakai
     * yang tersimpan. Pemicu yang dimatikan seminggu lalu menyimpan waktu yang
     * sudah lewat, dan menyalakannya kembali akan membuatnya langsung berjalan
     * — biasanya bukan itu yang dimaksud orang yang menekan tombolnya.
     */
    @PostMapping("/{name}/toggle")
    @Transactional
    public ResponseEntity<?> alihkan(@PathVariable String name) {
        UUID tenantId = CurrentUser.get().tenantId();

        Map<String, Object> pemicu = db.row("""
                SELECT enabled, cron, interval_minutes, timezone
                  FROM triggers WHERE tenant_id = ? AND name = ?
                """, tenantId, name);

        if (pemicu == null) return tidakAda();

        boolean akanAktif = !Boolean.TRUE.equals(pemicu.get("enabled"));

        OffsetDateTime berikutnya = akanAktif
                ? hitungBerikutnya((String) pemicu.get("cron"),
                        ((Number) pemicu.get("intervalMinutes")).intValue(),
                        Cron.zona((String) pemicu.get("timezone")))
                : null;

        db.exec("UPDATE triggers SET enabled = ?, next_run_at = ? WHERE tenant_id = ? AND name = ?",
                akanAktif, berikutnya, tenantId, name);

        return ResponseEntity.ok(Map.of("ok", true, "enabled", akanAktif));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> hapus(@PathVariable String name) {
        int terhapus = db.exec("DELETE FROM triggers WHERE tenant_id = ? AND name = ?",
                CurrentUser.get().tenantId(), name);

        return terhapus == 0 ? tidakAda() : ResponseEntity.ok(Map.of("ok", true));
    }

    // -----------------------------------------------------------------

    /**
     * Waktu jalan berikutnya: dari CRON kalau ada, kalau tidak dari selangnya.
     *
     * <p>Dipakai bersama oleh penyimpanan, penyalaan, dan penjadwal, supaya
     * ketiganya tidak bisa berbeda pendapat tentang kapan sesuatu jatuh tempo.
     */
    static OffsetDateTime hitungBerikutnya(String cron, int selangMenit, ZoneId zona) {
        OffsetDateTime sekarang = OffsetDateTime.now(ZoneOffset.UTC);

        if (cron == null || cron.isBlank()) {
            return sekarang.plusMinutes(Math.max(1, selangMenit));
        }

        ZonedDateTime next = Cron.next(cron, sekarang.toZonedDateTime(), zona);

        // Cron yang sah tapi tidak pernah cocok (mis. "0 0 31 2 *") menghasilkan
        // null. Dikembalikan null juga, dan pemanggilnya yang memutuskan —
        // penjadwal mematikan pemicunya dengan penjelasan.
        return next == null ? null : next.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static boolean zonaDikenal(String nama) {
        if (nama == null || nama.isBlank()) return false;

        try {
            ZoneId.of(nama.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static ResponseEntity<?> tidakAda() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Pemicu tidak ada."));
    }
}
