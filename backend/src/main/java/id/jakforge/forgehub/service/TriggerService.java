package id.jakforge.forgehub.service;

import id.jakforge.forgehub.common.ApiException;
import id.jakforge.forgehub.common.Cron;
import id.jakforge.forgehub.dto.TriggerRequest;
import id.jakforge.forgehub.repository.CatalogRepository;
import id.jakforge.forgehub.repository.TriggerRepository;
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
    private final CatalogRepository katalog;

    public TriggerService(TriggerRepository pemicu, CatalogRepository katalog) {
        this.pemicu = pemicu;
        this.katalog = katalog;
    }

    public List<Map<String, Object>> daftar(UUID tenantId) {
        return pemicu.semua(tenantId);
    }

    @Transactional
    public Map<String, Object> simpan(UUID tenantId, TriggerRequest minta) {
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

        if (!katalog.adaProses(tenantId, minta.processName())) {
            throw ApiException.salah(
                    "Proses '" + minta.processName() + "' belum diterbitkan ke ForgeHub.");
        }

        ZoneId zona = Cron.zona(minta.timezone());
        OffsetDateTime berikutnya = hitungBerikutnya(minta.cron(), minta.intervalMinutes(), zona);

        if (pemicu.ada(tenantId, minta.name())) {
            pemicu.perbarui(tenantId, minta.name(), minta.processName(), minta.robotName(),
                    minta.type(), minta.cron(), minta.intervalMinutes(), minta.enabled(),
                    berikutnya, minta.priority(), minta.timezone(), minta.runtimeType());
        } else {
            pemicu.buat(tenantId, minta.name(), minta.processName(), minta.robotName(),
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
    public Map<String, Object> alihkan(UUID tenantId, String nama) {
        Map<String, Object> baris = pemicu.satu(tenantId, nama);

        if (baris == null) throw ApiException.tidakAda("Pemicu tidak ada.");

        boolean akanAktif = !Boolean.TRUE.equals(baris.get("enabled"));

        OffsetDateTime berikutnya = akanAktif
                ? hitungBerikutnya((String) baris.get("cron"),
                        ((Number) baris.get("intervalMinutes")).intValue(),
                        Cron.zona((String) baris.get("timezone")))
                : null;

        pemicu.setAktif(tenantId, nama, akanAktif, berikutnya);

        Map<String, Object> hasil = new LinkedHashMap<>();
        hasil.put("ok", true);
        hasil.put("enabled", akanAktif);

        return hasil;
    }

    public void hapus(UUID tenantId, String nama) {
        if (pemicu.hapus(tenantId, nama) == 0) {
            throw ApiException.tidakAda("Pemicu tidak ada.");
        }
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
