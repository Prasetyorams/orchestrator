package id.jakforge.forgehub.audit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import id.jakforge.forgehub.common.Uuids;
import id.jakforge.forgehub.security.CurrentPrincipal;
import id.jakforge.forgehub.security.ForgeHubPrincipal;
import id.jakforge.forgehub.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

/**
 * Menulis jejak audit untuk setiap perubahan yang BERHASIL dilakukan orang.
 *
 * <p>Satu tempat untuk semua endpoint, bukan satu baris "catat audit" di
 * setiap service: baris seperti itu pasti terlupa pada endpoint berikutnya,
 * dan jejak yang bolong lebih menyesatkan daripada tidak ada jejak — orang
 * mengira yang tidak tercatat memang tidak pernah terjadi.
 *
 * <p>Yang dicatat hanya yang ada di {@link #RULES}. Lalu lintas robot —
 * denyut, kiriman catatan, laporan keadaan pekerjaan, pengambilan butir
 * antrean — sengaja tidak ada di sana: jumlahnya ribuan sehari dan
 * menenggelamkan satu-dua perubahan yang justru dicari.
 *
 * <p>Kegagalan menulis jejak TIDAK menggagalkan permintaannya. Perubahannya
 * sudah tersimpan saat jejak ditulis; menjawab galat sesudahnya membuat orang
 * mengulang sesuatu yang sebenarnya sudah berhasil.
 *
 * <p>Nama komponen dan tindakan ("Proses", "Simpan") DISIMPAN apa adanya dan
 * ditampilkan di Tenant › Audit; mengubah ejaannya memecah penyaring untuk
 * baris yang sudah tercatat.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    /** Nama folder atau proses yang diambil sebelum controllernya berjalan. */
    private static final String PRE_RESOLVED_TARGET = AuditInterceptor.class.getName() + ".preResolvedTarget";

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String USERNAME_FIELD = "username";
    private static final String FOLDER_ID_FIELD = "folderId";
    private static final String ID_PATH_VARIABLE = "id";
    private static final String TARGET_SEPARATOR = " · ";

    /** Medan badan yang mungkin menjadi nama sasaran; sisanya tidak pernah dibaca. */
    private static final Set<String> CAPTURED_BODY_FIELDS = Set.of("name", USERNAME_FIELD, "processName",
            "fileName", "version", "robotName", FOLDER_ID_FIELD);

    /** Dari mana satu bagian nama sasaran diambil. */
    enum SourceKind {
        /** Variabel jalur, mis. {name}. */
        PATH_VARIABLE,
        /** Medan di badan permintaan. */
        BODY_FIELD,
        /** Nama folder {id}, diambil SEBELUM controllernya berjalan. */
        FOLDER_OF_PATH_ID,
        /** Nama proses pekerjaan {id}, diambil SEBELUM controllernya berjalan. */
        JOB_OF_PATH_ID,
        /** Nama folder dari {@code ?folderId=}. */
        FOLDER_OF_PARAMETER,
        /** Nama folder dari medan {@code folderId} di badan. */
        FOLDER_OF_BODY
    }

    record TargetSource(SourceKind kind, String name) {
    }

    private static TargetSource path(String variable) {
        return new TargetSource(SourceKind.PATH_VARIABLE, variable);
    }

    private static TargetSource body(String field) {
        return new TargetSource(SourceKind.BODY_FIELD, field);
    }

    private static final TargetSource FOLDER_BY_ID = new TargetSource(SourceKind.FOLDER_OF_PATH_ID, ID_PATH_VARIABLE);
    private static final TargetSource JOB_BY_ID = new TargetSource(SourceKind.JOB_OF_PATH_ID, ID_PATH_VARIABLE);
    private static final TargetSource FOLDER_FROM_PARAMETER =
            new TargetSource(SourceKind.FOLDER_OF_PARAMETER, FOLDER_ID_FIELD);
    private static final TargetSource FOLDER_FROM_BODY = new TargetSource(SourceKind.FOLDER_OF_BODY, FOLDER_ID_FIELD);

    /**
     * Sasaran disusun dari sumber-sumbernya, dipisah " · ".
     *
     * <p>Nama proses dan pemicu unik per folder, jadi "Proses · Hapus · Tagihan"
     * tanpa foldernya tidak mengatakan proses YANG MANA yang hilang.
     */
    record AuditRule(HttpMethod method, String pathPattern, String component, String action,
                     List<TargetSource> targetSources) {
    }

    private static AuditRule rule(HttpMethod method, String pathPattern, String component, String action,
                                  TargetSource... targetSources) {
        return new AuditRule(method, pathPattern, component, action, List.of(targetSources));
    }

    static final List<AuditRule> RULES = List.of(
            rule(POST, LOGIN_PATH, "Sesi", "Masuk"),
            rule(PUT, "/api/auth/me", "Profil", "Ubah"),
            rule(POST, "/api/auth/password", "Profil", "Ganti kata sandi"),

            rule(POST, "/api/processes", "Proses", "Simpan", body("name"), FOLDER_FROM_BODY),
            rule(DELETE, "/api/processes/{name}", "Proses", "Hapus", path("name"), FOLDER_FROM_PARAMETER),
            rule(PUT, "/api/processes/{name}/folder", "Proses", "Pindahkan", path("name"), FOLDER_FROM_BODY),
            rule(POST, "/api/packages", "Paket", "Terbitkan", body("name"), body("version")),
            rule(DELETE, "/api/packages/{name}/{version}", "Paket", "Hapus", path("name"), path("version")),

            rule(POST, "/api/jobs", "Pekerjaan", "Jalankan", body("processName"), FOLDER_FROM_BODY),
            rule(POST, "/api/jobs/{id}/stop", "Pekerjaan", "Hentikan", JOB_BY_ID),
            rule(DELETE, "/api/jobs/{id}", "Pekerjaan", "Hapus", JOB_BY_ID),

            rule(POST, "/api/triggers", "Pemicu", "Simpan", body("name"), FOLDER_FROM_BODY),
            rule(POST, "/api/triggers/{name}/toggle", "Pemicu", "Alihkan status", path("name"), FOLDER_FROM_PARAMETER),
            rule(DELETE, "/api/triggers/{name}", "Pemicu", "Hapus", path("name"), FOLDER_FROM_PARAMETER),

            rule(POST, "/api/queues", "Antrean", "Buat", body("name"), FOLDER_FROM_BODY),
            rule(DELETE, "/api/queues/{name}", "Antrean", "Hapus", path("name")),
            rule(PUT, "/api/queues/{name}/folder", "Antrean", "Pindahkan", path("name"), FOLDER_FROM_BODY),
            rule(DELETE, "/api/queues/items/{id}", "Antrean", "Hapus butir", path("id")),

            rule(POST, "/api/assets", "Aset", "Simpan", body("name"), FOLDER_FROM_BODY),
            rule(DELETE, "/api/assets/{name}", "Aset", "Hapus", path("name")),
            rule(PUT, "/api/assets/{name}/folder", "Aset", "Pindahkan", path("name"), FOLDER_FROM_BODY),
            rule(POST, "/api/credentials", "Aset", "Simpan", body("name")),
            rule(DELETE, "/api/credentials/{name}", "Aset", "Hapus", path("name")),

            rule(POST, "/api/buckets", "Ember Penyimpanan", "Buat", body("name"), FOLDER_FROM_BODY),
            rule(DELETE, "/api/buckets/{name}", "Ember Penyimpanan", "Hapus", path("name")),
            rule(PUT, "/api/buckets/{name}/folder", "Ember Penyimpanan", "Pindahkan", path("name"), FOLDER_FROM_BODY),
            rule(POST, "/api/buckets/{name}/files", "Ember Penyimpanan", "Unggah berkas", path("name"),
                    body("fileName")),
            rule(DELETE, "/api/buckets/{name}/files/{id}", "Ember Penyimpanan", "Hapus berkas", path("name")),

            rule(POST, "/api/robots", "Robot", "Buat", body("name")),
            rule(DELETE, "/api/robots/{name}", "Robot", "Hapus", path("name")),
            rule(POST, "/api/machines", "Mesin", "Buat", body("name")),
            rule(DELETE, "/api/machines/{name}", "Mesin", "Hapus", path("name")),
            rule(POST, "/api/environments", "Lingkungan", "Buat", body("name")),
            rule(DELETE, "/api/environments/{name}", "Lingkungan", "Hapus", path("name")),

            rule(POST, "/api/users", "Pengguna", "Simpan", body(USERNAME_FIELD)),
            rule(DELETE, "/api/users/{username}", "Pengguna", "Hapus", path(USERNAME_FIELD)),

            rule(POST, "/api/folders", "Folder", "Buat", body("name")),
            rule(PUT, "/api/folders/{id}", "Folder", "Ubah", FOLDER_BY_ID),
            rule(DELETE, "/api/folders/{id}", "Folder", "Hapus", FOLDER_BY_ID),
            rule(POST, "/api/folders/{id}/users", "Folder", "Tugaskan pengguna", FOLDER_BY_ID, body(USERNAME_FIELD)),
            rule(DELETE, "/api/folders/{id}/users/{username}", "Folder", "Lepas pengguna", FOLDER_BY_ID,
                    path(USERNAME_FIELD)),
            rule(POST, "/api/folders/{id}/robots", "Folder", "Tugaskan robot", FOLDER_BY_ID, body("robotName")),
            rule(DELETE, "/api/folders/{id}/robots/{name}", "Folder", "Lepas robot", FOLDER_BY_ID, path("name")),

            rule(DELETE, "/api/logs", "Catatan", "Bersihkan"));

    private final AuditService auditService;
    private final JsonFactory jsonFactory = new JsonFactory();

    /**
     * Nama folder dan proses diambil SEBELUM controllernya berjalan: sesudah
     * folder atau pekerjaannya dihapus, yang tersisa hanya id — dan jejak
     * "Folder · Hapus · 3f2a..." tidak memberi tahu siapa pun apa yang hilang.
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        try {
            AuditRule rule = findRule(request);
            Optional<ForgeHubPrincipal> principal = CurrentPrincipal.find();

            if (rule == null || principal.isEmpty()) return true;

            UUID id = Uuids.parseOrNull(pathVariables(request).get(ID_PATH_VARIABLE));
            if (id == null) return true;

            UUID tenantId = principal.get().tenantId();

            if (rule.targetSources().contains(FOLDER_BY_ID)) {
                request.setAttribute(PRE_RESOLVED_TARGET, auditService.findFolderName(tenantId, id).orElse(null));
            }

            if (rule.targetSources().contains(JOB_BY_ID)) {
                request.setAttribute(PRE_RESOLVED_TARGET, auditService.findJobProcessName(tenantId, id).orElse(null));
            }
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
            AuditRule rule = findRule(request);
            if (rule == null) return;

            Map<String, String> bodyFields = capturedBodyFields(request);
            String detail = request.getMethod() + " " + request.getRequestURI();

            if (LOGIN_PATH.equals(rule.pathPattern())) {
                // Belum ada token saat masuk; penyewanya dicari dari nama penggunanya.
                auditService.recordLogin(bodyFields.get(USERNAME_FIELD), rule.component(), rule.action(), detail);
                return;
            }

            Optional<ForgeHubPrincipal> principal = CurrentPrincipal.find();
            if (principal.isEmpty()) return;

            String target = describeTarget(rule, principal.get(), request, bodyFields);
            auditService.record(principal.get(), rule.component(), rule.action(), target, detail);
        } catch (Exception e) {
            log.warn("Jejak audit gagal ditulis untuk {} {}.", request.getMethod(), request.getRequestURI(), e);
        }
    }

    // -----------------------------------------------------------------
    // Alat
    // -----------------------------------------------------------------

    private static AuditRule findRule(HttpServletRequest request) {
        Object pathPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pathPattern == null) return null;

        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        for (AuditRule rule : RULES) {
            if (rule.method().equals(method) && rule.pathPattern().equals(pathPattern.toString())) return rule;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    /**
     * Nama sasaran, disusun dari sumber-sumber aturannya.
     *
     * <p>Nama folder dari parameter dan badan dicari SESUDAH controllernya
     * berjalan, dan hanya kalau aturannya memintanya: folder yang disebut di
     * sana tidak pernah yang baru saja dihapus.
     */
    private String describeTarget(AuditRule rule, ForgeHubPrincipal principal, HttpServletRequest request,
                                  Map<String, String> bodyFields) {
        Map<String, String> pathVariables = pathVariables(request);
        List<String> parts = new ArrayList<>();

        for (TargetSource source : rule.targetSources()) {
            String value = switch (source.kind()) {
                case PATH_VARIABLE -> pathVariables.get(source.name());
                case BODY_FIELD -> bodyFields.get(source.name());
                case FOLDER_OF_PARAMETER -> folderName(principal, request.getParameter(source.name()));
                case FOLDER_OF_BODY -> folderName(principal, bodyFields.get(source.name()));
                case FOLDER_OF_PATH_ID, JOB_OF_PATH_ID -> (String) request.getAttribute(PRE_RESOLVED_TARGET);
            };

            if (value != null && !value.isBlank()) parts.add(value.trim());
        }

        return parts.isEmpty() ? null : String.join(TARGET_SEPARATOR, parts);
    }

    private String folderName(ForgeHubPrincipal principal, String folderId) {
        UUID id = Uuids.parseOrNull(folderId);
        return id == null ? null : auditService.findFolderName(principal.tenantId(), id).orElse(null);
    }

    /**
     * Medan teratas badan JSON yang tersalin oleh {@link AuditFilter}.
     *
     * <p>Dibaca sebagai ALIRAN, bukan diurai utuh: salinannya berhenti di
     * batas 16 KB, jadi JSON-nya bisa terpotong di tengah. Pengurai aliran
     * memberi semua medan sebelum titik potong itu; pengurai utuh tidak
     * memberi apa pun.
     */
    private Map<String, String> capturedBodyFields(HttpServletRequest request) {
        Map<String, String> fields = new HashMap<>();

        ContentCachingRequestWrapper cachedRequest =
                WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);

        if (cachedRequest == null) return fields;

        byte[] content = cachedRequest.getContentAsByteArray();
        if (content.length == 0) return fields;

        try (JsonParser parser = jsonFactory.createParser(content)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return fields;

            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String fieldName = parser.currentName();
                JsonToken value = parser.nextToken();

                if (value == null) break;

                if (value.isScalarValue()) {
                    if (CAPTURED_BODY_FIELDS.contains(fieldName)) fields.put(fieldName, parser.getValueAsString());
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            // Terpotong di batas salinan: yang sudah terbaca tetap dipakai.
        }

        return fields;
    }
}
