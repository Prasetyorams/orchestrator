package id.jakforge.openorchestrator.dto.request;

/**
 * Persetujuan "Sambungkan Open Assistant" dari halaman dasbor
 * {@code /assistant/connect}: parameter yang dibawa Open Assistant ke peramban,
 * diteruskan dasbor apa adanya. Diperiksa AssistantSignInService.
 *
 * @param machine nama komputer yang disebut Open Assistant; hanya ditampilkan
 */
public record AssistantCodeRequest(String client, String redirectUri, String codeChallenge,
                                   String codeChallengeMethod, String state, String machine) {
}
