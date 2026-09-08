package id.jakforge.forgehub.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Halaman akar: mengarahkan ke dasbor.
 *
 * <p>Ada karena ForgeHub .NET menyajikan API DAN dasbor dari satu alamat yang
 * sama, sedangkan di sini keduanya terpisah — Next.js di 3000, API di 8080.
 * Orang yang terbiasa membuka 8080 akan tetap membukanya, dan yang diterimanya
 * adalah {@code {"error":"Token tidak sah atau sudah kedaluwarsa."}}: jawaban
 * yang benar untuk permintaan API tanpa token, tapi jalan buntu tanpa petunjuk
 * bagi orang yang sebenarnya mencari dasbor.
 *
 * <p>Yang dikembalikan adalah pengalihan, bukan halaman penjelasan. Penjelasan
 * menuntut orangnya membaca lalu mengetik ulang alamat; pengalihan langsung
 * membawanya ke tempat yang dicari.
 */
@RestController
public class AkarApi {

    private final String dasbor;

    /**
     * Alamat dasbor diambil dari CORS_ORIGINS, bukan ditulis tetap.
     *
     * <p>Keduanya menjawab pertanyaan yang sama — "di mana dasbornya" — dan dua
     * tempat yang menjawabnya sendiri-sendiri akan berbeda begitu salah satunya
     * diubah. Kalau berisi beberapa alamat, yang dipakai yang pertama.
     */
    public AkarApi(@Value("${forgehub.cors.allowed-origins}") String allowedOrigins) {
        String pertama = allowedOrigins.split(",")[0].trim();
        this.dasbor = pertama.isEmpty() ? "http://localhost:3000" : pertama;
    }

    @GetMapping("/")
    public ResponseEntity<Void> akar() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, dasbor)
                .build();
    }
}
