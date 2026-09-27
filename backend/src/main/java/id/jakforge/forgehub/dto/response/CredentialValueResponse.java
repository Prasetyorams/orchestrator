package id.jakforge.forgehub.dto.response;

/** Nama pengguna dan kata sandi terbuka — hanya untuk activity Get Credential. */
public record CredentialValueResponse(String username, String password) {
}
