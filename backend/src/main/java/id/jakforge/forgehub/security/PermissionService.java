package id.jakforge.forgehub.security;

import id.jakforge.forgehub.config.ForgeHubProperties;
import id.jakforge.forgehub.model.RoleNames;
import id.jakforge.forgehub.model.UserAccess;
import id.jakforge.forgehub.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Izin peran seseorang: apa yang boleh dilakukannya SAAT INI.
 *
 * <p>Izin dibaca dari peran orang itu saat ini di basis data, bukan dari peran
 * yang tertulis di tokennya: token berlaku berjam-jam, dan peran yang dicabut
 * harus berhenti berlaku sekarang, bukan besok pagi. Supaya denyut robot tiap
 * beberapa detik tidak menjadi kueri tiap beberapa detik, hasilnya diingat
 * selama {@code forgehub.permission-cache.ttl} per orang, dan dilupakan
 * seketika begitu peran atau pengguna diubah lewat ForgeHub.
 *
 * <p>Peran Administrator selalu berarti semuanya, apa pun isi barisnya (lihat
 * {@link RoleNames#ADMINISTRATOR}).
 */
@Service
@RequiredArgsConstructor
public class PermissionService implements PermissionChecker {

    private record CachedPatterns(Set<String> patterns, Instant expiresAt) {
    }

    private final UserRepository userRepository;
    private final ForgeHubProperties properties;
    private final Clock clock;

    private final Map<UUID, CachedPatterns> cache = new ConcurrentHashMap<>();

    /** Pola izin peran orang itu saat ini; kosong untuk pengguna nonaktif atau yang sudah dihapus. */
    public Set<String> patternsOf(ForgeHubPrincipal principal) {
        Instant now = clock.instant();
        CachedPatterns cached = cache.get(principal.userId());

        if (cached != null && cached.expiresAt().isAfter(now)) return cached.patterns();

        Set<String> patterns = loadPatterns(principal);
        cache.put(principal.userId(), new CachedPatterns(patterns, now.plus(properties.permissionCache().ttl())));

        return patterns;
    }

    @Override
    public boolean isAllowed(ForgeHubPrincipal principal, String permission) {
        return PermissionCatalog.matches(patternsOf(principal), permission);
    }

    /** Sesudah peran atau pengguna berubah: yang tersimpan tidak boleh berlaku lebih lama lagi. */
    public void invalidateCache() {
        cache.clear();
    }

    private Set<String> loadPatterns(ForgeHubPrincipal principal) {
        // Pengguna yang dinonaktifkan tidak kehilangan tokennya — token tidak
        // bisa ditarik kembali — tapi kehilangan semua izinnya sekarang juga.
        return userRepository.findAccess(principal.userId(), principal.tenantId())
                .filter(UserAccess::active)
                .map(PermissionService::patternsOfRole)
                .orElse(Set.of());
    }

    private static Set<String> patternsOfRole(UserAccess access) {
        return RoleNames.ADMINISTRATOR.equals(access.role())
                ? Set.of(Permissions.ALL)
                : PermissionCatalog.parsePatterns(access.permissions());
    }
}
