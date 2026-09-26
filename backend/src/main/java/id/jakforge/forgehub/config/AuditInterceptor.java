package id.jakforge.forgehub.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import id.jakforge.forgehub.repository.AuditRepository;
import id.jakforge.forgehub.repository.Db;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Menulis jejak audit untuk setiap perubahan yang BERHASIL dilakukan orang.
 *
 * <p>Satu tempat untuk semua endpoint, bukan satu baris "catat audit" di
 * setiap service: baris seperti itu pasti terlupa pada endpoint berikutnya,
 * dan jejak yang bolong lebih menyesatkan daripada tidak ada jejak — orang
 * mengira yang tidak tercatat memang tidak pernah terjadi.
 *
 * <p>Yang dicatat hanya yang ada di {@link #ATURAN}. Lalu lintas robot —
 * denyut, kiriman catatan, laporan keadaan pekerjaan, pengambilan butir
 * antrean — sengaja tidak ada di sana: jumlahnya ribuan sehari dan
 * menenggelamkan satu-dua perubahan yang justru dicari.
 *
 * <p>Kegagalan menulis jejak TIDAK menggagalkan permintaannya. Perubahannya
 * sudah tersimpan saat jejak ditulis; menjawab galat sesudahnya membuat orang
 * mengulang sesuatu yang sebenarnya sudah berhasil.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    private static final String PRA = AuditInterceptor.class.getName() + ".pra";

    /** Medan badan yang mungkin menjadi nama sasaran; sisanya tidak pernah dibaca. */
    private static final Set<String> MEDAN = Set.of("name", "username", "processName", "fileName",
            "version", "robotName", "folderId");

    /**
     * Sasaran disusun dari sumber-sumber ini, dipisah " · ":
     * {@code path:x} variabel jalur, {@code body:x} medan badan, {@code folder}
     * nama folder dari {id}, {@code job} nama proses pekerjaan {id},
     * {@code folder:param} nama folder dari {@code ?folderId=}, dan
     * {@code folder:badan} nama folder dari medan {@code folderId} di badan.
     *
     * <p>Nama proses dan pemicu unik per folder, jadi "Proses · Hapus · Tagihan"
     * tanpa foldernya tidak mengatakan proses YANG MANA yang hilang.
     */
    private record Aturan(String metode, String pola, String komponen, String aksi, String... sumber) {
    }

    private static final List<Aturan> ATURAN = List.of(
            new Aturan("POST", "/api/auth/login", "Sesi", "Masuk"),
            new Aturan("PUT", "/api/auth/me", "Profil", "Ubah"),
            new Aturan("POST", "/api/auth/password", "Profil", "Ganti kata sandi"),

            new Aturan("POST", "/api/processes", "Proses", "Simpan", "body:name", "folder:badan"),
            new Aturan("DELETE", "/api/processes/{name}", "Proses", "Hapus", "path:name", "folder:param"),
            new Aturan("PUT", "/api/processes/{name}/folder", "Proses", "Pindahkan", "path:name", "folder:badan"),
            new Aturan("POST", "/api/packages", "Paket", "Terbitkan", "body:name", "body:version"),
            new Aturan("DELETE", "/api/packages/{name}/{version}", "Paket", "Hapus", "path:name", "path:version"),

            new Aturan("POST", "/api/jobs", "Pekerjaan", "Jalankan", "body:processName", "folder:badan"),
            new Aturan("POST", "/api/jobs/{id}/stop", "Pekerjaan", "Hentikan", "job"),
            new Aturan("DELETE", "/api/jobs/{id}", "Pekerjaan", "Hapus", "job"),

            new Aturan("POST", "/api/triggers", "Pemicu", "Simpan", "body:name", "folder:badan"),
            new Aturan("POST", "/api/triggers/{name}/toggle", "Pemicu", "Alihkan status", "path:name", "folder:param"),
            new Aturan("DELETE", "/api/triggers/{name}", "Pemicu", "Hapus", "path:name", "folder:param"),

            new Aturan("POST", "/api/queues", "Antrean", "Buat", "body:name", "folder:badan"),
            new Aturan("DELETE", "/api/queues/{name}", "Antrean", "Hapus", "path:name"),
            new Aturan("PUT", "/api/queues/{name}/folder", "Antrean", "Pindahkan", "path:name", "folder:badan"),
            new Aturan("DELETE", "/api/queues/items/{id}", "Antrean", "Hapus butir", "path:id"),

            new Aturan("POST", "/api/assets", "Aset", "Simpan", "body:name", "folder:badan"),
            new Aturan("DELETE", "/api/assets/{name}", "Aset", "Hapus", "path:name"),
            new Aturan("PUT", "/api/assets/{name}/folder", "Aset", "Pindahkan", "path:name", "folder:badan"),
            new Aturan("POST", "/api/credentials", "Aset", "Simpan", "body:name"),
            new Aturan("DELETE", "/api/credentials/{name}", "Aset", "Hapus", "path:name"),

            new Aturan("POST", "/api/buckets", "Ember Penyimpanan", "Buat", "body:name", "folder:badan"),
            new Aturan("DELETE", "/api/buckets/{name}", "Ember Penyimpanan", "Hapus", "path:name"),
            new Aturan("PUT", "/api/buckets/{name}/folder", "Ember Penyimpanan", "Pindahkan", "path:name",
                    "folder:badan"),
            new Aturan("POST", "/api/buckets/{name}/files", "Ember Penyimpanan", "Unggah berkas",
                    "path:name", "body:fileName"),
            new Aturan("DELETE", "/api/buckets/{name}/files/{id}", "Ember Penyimpanan", "Hapus berkas", "path:name"),

            new Aturan("POST", "/api/robots", "Robot", "Buat", "body:name"),
            new Aturan("DELETE", "/api/robots/{name}", "Robot", "Hapus", "path:name"),
            new Aturan("POST", "/api/machines", "Mesin", "Buat", "body:name"),
            new Aturan("DELETE", "/api/machines/{name}", "Mesin", "Hapus", "path:name"),
            new Aturan("POST", "/api/environments", "Lingkungan", "Buat", "body:name"),
            new Aturan("DELETE", "/api/environments/{name}", "Lingkungan", "Hapus", "path:name"),

            new Aturan("POST", "/api/users", "Pengguna", "Simpan", "body:username"),
            new Aturan("DELETE", "/api/users/{username}", "Pengguna", "Hapus", "path:username"),

            new Aturan("POST", "/api/folders", "Folder", "Buat", "body:name"),
            new Aturan("PUT", "/api/folders/{id}", "Folder", "Ubah", "folder"),
            new Aturan("DELETE", "/api/folders/{id}", "Folder", "Hapus", "folder"),
            new Aturan("POST", "/api/folders/{id}/users", "Folder", "Tugaskan pengguna", "folder", "body:username"),
            new Aturan("DELETE", "/api/folders/{id}/users/{username}", "Folder", "Lepas pengguna",
                    "folder", "path:username"),
            new Aturan("POST", "/api/folders/{id}/robots", "Folder", "Tugaskan robot", "folder", "body:robotName"),
            new Aturan("DELETE", "/api/folders/{id}/robots/{name}", "Folder", "Lepas robot", "folder", "path:name"),

            new Aturan("DELETE", "/api/logs", "Catatan", "Bersihkan"));

    private final AuditRepository audit;
    private final JsonFactory json = new JsonFactory();

    public AuditInterceptor(AuditRepository audit) {
        this.audit = audit;
    }

    /**
     * Nama folder dan proses diambil SEBELUM controllernya berjalan: sesudah
     * folder atau pekerjaannya dihapus, yang tersisa hanya id — dan jejak
     * "Folder · Hapus · 3f2a..." tidak memberi tahu siapa pun apa yang hilang.
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        try {
            Aturan aturan = aturanUntuk(request);
            ForgeHubPrincipal p = pemanggil();

            if (aturan == null || p == null) return true;

            List<String> sumber = List.of(aturan.sumber());
            UUID id = Db.uuid(variabel(request).get("id"));

            if (id == null) return true;

            if (sumber.contains("folder")) request.setAttribute(PRA, audit.namaFolder(p.tenantId(), id));
            if (sumber.contains("job")) request.setAttribute(PRA, audit.prosesPekerjaan(p.tenantId(), id));
        } catch (Exception e) {
            log.warn("Jejak audit tidak bisa disiapkan untuk {} {}.", request.getMethod(), request.getRequestURI(), e);
        }

        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        if (ex != null || response.getStatus() < 200 || response.getStatus() >= 300) return;

        try {
            Aturan aturan = aturanUntuk(request);
            if (aturan == null) return;

            Map<String, String> badan = badan(request);
            String rincian = request.getMethod() + " " + request.getRequestURI();

            if ("/api/auth/login".equals(aturan.pola())) {
                // Belum ada token saat masuk; penyewanya dicari dari nama penggunanya.
                String username = badan.get("username");
                UUID penyewa = username == null ? null : audit.penyewaPengguna(username);

                if (penyewa != null) audit.catat(penyewa, username, aturan.komponen(), aturan.aksi(), null, rincian);
                return;
            }

            ForgeHubPrincipal p = pemanggil();
            if (p == null) return;

            // Nama folder dicari sesudahnya, dan hanya kalau aturannya memintanya:
            // folder yang disebut di sini tidak pernah yang baru saja dihapus.
            List<String> sumber = List.of(aturan.sumber());
            Map<String, String> folder = new HashMap<>();

            if (sumber.contains("folder:param")) folder.put("param", namaFolder(p, request.getParameter("folderId")));
            if (sumber.contains("folder:badan")) folder.put("badan", namaFolder(p, badan.get("folderId")));

            audit.catat(p.tenantId(), p.username(), aturan.komponen(), aturan.aksi(),
                    sasaran(aturan, variabel(request), badan, folder, (String) request.getAttribute(PRA)), rincian);
        } catch (Exception e) {
            log.warn("Jejak audit gagal ditulis untuk {} {}.", request.getMethod(), request.getRequestURI(), e);
        }
    }

    // -----------------------------------------------------------------
    // Alat
    // -----------------------------------------------------------------

    private static Aturan aturanUntuk(HttpServletRequest request) {
        Object pola = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pola == null) return null;

        for (Aturan a : ATURAN) {
            if (a.metode().equals(request.getMethod()) && a.pola().equals(pola.toString())) return a;
        }

        return null;
    }

    private static ForgeHubPrincipal pemanggil() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof ForgeHubPrincipal p ? p : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> variabel(HttpServletRequest request) {
        Object v = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return v instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
    }

    private String namaFolder(ForgeHubPrincipal p, String id) {
        UUID folder = Db.uuid(id);
        return folder == null ? null : audit.namaFolder(p.tenantId(), folder);
    }

    private static String sasaran(Aturan aturan, Map<String, String> jalur, Map<String, String> badan,
                                  Map<String, String> folder, String pra) {
        List<String> bagian = new ArrayList<>();

        for (String s : aturan.sumber()) {
            String nilai;

            if (s.startsWith("path:")) nilai = jalur.get(s.substring(5));
            else if (s.startsWith("body:")) nilai = badan.get(s.substring(5));
            else if (s.startsWith("folder:")) nilai = folder.get(s.substring(7));
            else nilai = pra;

            if (nilai != null && !nilai.isBlank()) bagian.add(nilai.trim());
        }

        return bagian.isEmpty() ? null : String.join(" · ", bagian);
    }

    /**
     * Medan teratas badan JSON yang tersalin oleh {@link AuditFilter}.
     *
     * <p>Dibaca sebagai ALIRAN, bukan diurai utuh: salinannya berhenti di
     * batas 16 KB, jadi JSON-nya bisa terpotong di tengah. Pengurai aliran
     * memberi semua medan sebelum titik potong itu; pengurai utuh tidak
     * memberi apa pun.
     */
    private Map<String, String> badan(HttpServletRequest request) {
        Map<String, String> hasil = new HashMap<>();

        ContentCachingRequestWrapper salinan =
                WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);

        if (salinan == null) return hasil;

        byte[] isi = salinan.getContentAsByteArray();
        if (isi.length == 0) return hasil;

        try (JsonParser p = json.createParser(isi)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return hasil;

            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String medan = p.currentName();
                JsonToken nilai = p.nextToken();

                if (nilai == null) break;

                if (nilai.isScalarValue()) {
                    if (MEDAN.contains(medan)) hasil.put(medan, p.getValueAsString());
                } else {
                    p.skipChildren();
                }
            }
        } catch (IOException e) {
            // Terpotong di batas salinan: yang sudah terbaca tetap dipakai.
        }

        return hasil;
    }
}
