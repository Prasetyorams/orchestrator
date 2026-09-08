package id.jakforge.forgehub.security;

import java.util.UUID;

/**
 * Siapa yang sedang memanggil API.
 *
 * tenantId ikut dibawa di sini supaya setiap service bisa menyaring
 * datanya tanpa menerima tenantId dari badan permintaan — nilai yang
 * datang dari klien tidak boleh menentukan data siapa yang terlihat.
 */
public record ForgeHubPrincipal(UUID userId, UUID tenantId, String username, String role) {
}
