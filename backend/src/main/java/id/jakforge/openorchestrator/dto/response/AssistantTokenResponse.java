package id.jakforge.openorchestrator.dto.response;

/**
 * Jawaban penukaran kode dan pembaruan token Open Assistant.
 *
 * @param token        token akses pengguna, berlaku satu jam
 * @param refreshToken sekali pakai: setiap pembaruan memberi yang baru, dan yang lama langsung mati
 * @param robotName    robot attended yang dipakai Open Assistant untuk denyut dan job v1
 */
public record AssistantTokenResponse(String token, String expiresAt, String refreshToken, User user,
                                     String robotName) {

    public record User(String username, String displayName) {
    }
}
