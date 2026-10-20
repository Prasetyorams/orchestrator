package id.jakforge.openorchestrator.dto.response;

import id.jakforge.openorchestrator.security.ad.AdAccount;

/**
 * Pencarian akun Windows AD untuk dialog Pengguna (GET /api/users/ad-lookup, V19).
 * Tidak pernah memuat sandi atau atribut lain di luar yang ditampilkan.
 *
 * @param enabled         masuk Windows AD diaktifkan di server
 * @param lookupAvailable ada akun layanan; tanpa itu identitasnya ditautkan saat pengguna masuk pertama
 * @param found           null kalau tidak dicari
 * @param linkedTo        pengguna Open Orchestrator yang sudah memakai akun AD ini, atau null
 */
public record AdLookupResponse(boolean enabled, boolean lookupAvailable, String label, Boolean found,
                               String samAccountName, String displayName, String email, String objectGuid,
                               String distinguishedName, Boolean accountEnabled, String linkedTo) {

    public static AdLookupResponse unavailable(boolean enabled, boolean lookupAvailable, String label) {
        return new AdLookupResponse(enabled, lookupAvailable, label, null, null, null, null, null, null, null, null);
    }

    public static AdLookupResponse notFound(String label) {
        return new AdLookupResponse(true, true, label, false, null, null, null, null, null, null, null);
    }

    public static AdLookupResponse found(String label, AdAccount a, String linkedTo) {
        return new AdLookupResponse(true, true, label, true, a.samAccountName(), a.displayName(), a.email(),
                a.objectGuid().toString(), a.distinguishedName(), a.enabled(), linkedTo);
    }
}
