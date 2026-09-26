package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Cron;
import id.jakforge.forgehub.dto.TriggerRequest;
import id.jakforge.forgehub.repository.TriggerRepository;
import id.jakforge.forgehub.security.Penjaga;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Aturan tentang pemicu terjadwal. */
@Service
public class TriggerService {

    private final TriggerRepository pemicu;
    private final CatalogService katalog;

    public TriggerService(TriggerRepository pemicu, CatalogService katalog) {
        this.pemicu = pemicu;
        this.katalog = katalog;
    }

    /** @param folderId null berarti seluruh penyewa. */
    public List<Map<String, Object>> daftar(UUID tenantId, UUID folderId) {
        return pemicu.semua(tenantId, folderId);
    }

    /**
     * Pemicu tinggal di folder PROSESNYA, dan namanya unik di folder itu.
     *
     * @param folderId folder yang sedang dibuka, sudah diperiksa haknya; null
     *                 berarti proses bernama itu di mana pun ia berada (lihat
     *                 CatalogService.pilihFolder)
     * @param penjaga  triggers.create untuk yang baru, triggers.update untuk yang sudah ada
     */
    @Transactional
    public Map<String, Object> simpan(UUID tenantId, TriggerRequest minta, UUID folderId, Penjaga penjaga) {
        if (minta.name() == null) throw ApiException.salah("Nama pemicu wajib diisi.");
        if (minta.processName() == null) throw ApiException.salah("processName wajib diisi.");

        // Cron DIVALIDASI di sini, bukan dibiarkan sampai penjadwal. Ekspresi
        // yang salah baru ketahuan pada putaran penjadwal berikutnya, dan orang
        // yang menekan "Buat pemicu" sudah pergi.
        if (minta.pakaiCron() && !Cron.isValid(minta.cron())) {
            throw ApiException.salah("Ekspresi cron tidak sah: " + minta.cron()
                    + ". Bentuknya lima ruas: menit jam tanggal bulan hari, "
                    + "mis. \"0 7 * * 1-5\" untuk tiap hari kerja pukul 07:00.");
        }

        if (!minta.pakaiCron() && minta.intervalMinutes() < 1) {
            throw ApiException.salah("Selang waktu minimal 1 menit.");
        }

        // Nama zona diperiksa juga. Penjadwal memang jatuh ke UTC untuk nama
        // yang tidak dikenal, tapi jatuh diam-diam berarti pemicunya berjalan
        // tujuh jam meleset tanpa ada yang tahu sebabnya.
        if (!"UTC".equals(minta.timezone()) && !zonaDikenal(minta.timezone())) {
            throw ApiException.salah("Zona waktu tidak dikenal: '" + minta.timezone()
                    + "'. Pakai nama IANA, mis. \"Asia/Jakarta\".");
        }

        UUID folder = katalog.folderProses(tenantId, minta.processName(), folderId);

        if (folder == null) {
            throw ApiException.salah(folderId == null
                    ? "Proses '" + minta.processName() + "' belum diterbitkan ke ForgeHub."
                    : "Proses '" + minta.processName() + "' tidak ada di folder ini.");
        }

        ZoneId zona = Cron.zona(minta.timezone());
        OffsetDateTime berikutnya = hitungBerikutnya(minta.cron(), minta.intervalMinutes(), zona);

        boolean sudahAda = pemicu.ada(tenantId, folder, minta.name());
        penjaga.perluSimpan("triggers", sudahAda);

        if (sudahAda) {
            pemicu.perbarui(tenantId, folder, minta.name(), minta.processName(), minta.robotName(),
                    minta.type(), minta.cron(), minta.intervalMinutes(), minta.enabled(),
                    berikutnya, minta.priority(), minta.timezone(), minta.runtimeType());
        } else {
            pemicu.buat(tenantId, folder, minta.name(), minta.processName(), minta.robotName(),
                    minta.type(), minta.cron(), minta.intervalMinutes(), minta.enabled(),
                    berikutnya, minta.priority(), minta.timezone(), minta.runtimeType());
        }

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("nextRunAt", String.valueOf(berikutnya));

        return hasil;
    }

    /**
     * Nyalakan atau matikan.
     *
     * <p>Waktu jalan berikutnya dihitung ULANG saat dinyalakan, bukan dipakai
     * yang tersimpan. Pemicu yang dimatikan seminggu lalu menyimpan waktu yang
     * sudah lewat, dan menyalakannya kembali akan membuatnya langsung berjalan
     * — biasanya bukan itu yang dimaksud orang yang menekan tombolnya.
     */
    @Transactional
    public Map<String, Object> alihkan(UUID tenantId, String nama, UUID folderId) {
        UUID folder = folderPemicu(tenantId, nama, folderId);
        Map<String, Object> baris = pemicu.satu(tenantId, folder, nama);

        if (baris == null) throw ApiException.tidakAda("Pemicu tidak ada.");

        boolean akanAktif = !Boolean.TRUE.equals(baris.get("enabled"));

        OffsetDateTime berikutnya = akanAktif
                ? hitungBerikutnya((String) baris.get("cron"),
                        ((Number) baris.get("intervalMinutes")).intValue(),
                        Cron.zona((String) baris.get("timezone")))
                : null;

        pemicu.setAktif(tenantId, folder, nama, akanAktif, berikutnya);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("enabled", akanAktif);

        return hasil;
    }

    public void hapus(UUID tenantId, String nama, UUID folderId) {
        if (pemicu.hapus(tenantId, folderPemicu(tenantId, nama, folderId), nama) == 0) {
            throw ApiException.tidakAda("Pemicu tidak ada.");
        }
    }

    /** Folder pemicu yang dimaksud; aturannya sama dengan proses (CatalogService.pilihFolder). */
    private UUID folderPemicu(UUID tenantId, String nama, UUID folderId) {
        UUID folder = CatalogService.pilihFolder(pemicu.tempat(tenantId, nama), folderId,
                "Pemicu '" + nama + "' ada di beberapa folder. Sebutkan foldernya.");

        if (folder == null) throw ApiException.tidakAda("Pemicu tidak ada.");

        return folder;
    }

    /**
     * Waktu jalan berikutnya: dari CRON kalau ada, kalau tidak dari selangnya.
     *
     * <p>Dipakai bersama oleh penyimpanan, penyalaan, dan penjadwal, supaya
     * ketiganya tidak bisa berbeda pendapat tentang kapan sesuatu jatuh tempo.
     *
     * <p>Mengembalikan null untuk cron yang sah tapi tidak pernah cocok (mis.
     * "0 0 31 2 *"); pemanggilnya yang memutuskan apa artinya.
     */
    public static OffsetDateTime hitungBerikutnya(String cron, int selangMenit, ZoneId zona) {
        OffsetDateTime sekarang = OffsetDateTime.now(ZoneOffset.UTC);

        if (cron == null || cron.isBlank()) {
            return sekarang.plusMinutes(Math.max(1, selangMenit));
        }

        ZonedDateTime next = Cron.next(cron, sekarang.toZonedDateTime(), zona);

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
}
