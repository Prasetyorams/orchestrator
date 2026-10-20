package id.jakforge.openorchestrator.security.ad;

import java.util.UUID;

/**
 * Akun Windows AD sebagaimana dibaca dari direktori — tanpa kata sandi.
 *
 * @param objectGuid        identitas STABIL akun (objectGUID); tidak berubah walau namanya diganti,
 *                          dan berbeda kalau akunnya dihapus lalu dibuat ulang dengan nama yang sama
 * @param samAccountName    nama masuk (bisa berubah)
 * @param allowedByGroups   anggota (langsung atau bersarang) salah satu grup yang diizinkan, atau
 *                          tidak ada syarat grup sama sekali
 */
public record AdAccount(UUID objectGuid, String samAccountName, String userPrincipalName, String displayName,
                        String email, String distinguishedName, boolean enabled, boolean allowedByGroups) {
}
