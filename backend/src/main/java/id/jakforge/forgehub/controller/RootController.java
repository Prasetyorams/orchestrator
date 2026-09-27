package id.jakforge.forgehub.controller;

import id.jakforge.forgehub.config.ForgeHubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
@RequiredArgsConstructor
public class RootController {

    private final ForgeHubProperties properties;

    /** Alamat dasbor: asal pertama di CORS_ORIGINS (lihat {@link ForgeHubProperties.Cors#dashboardUrl()}). */
    @GetMapping("/")
    public ResponseEntity<Void> redirectToDashboard() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, properties.cors().dashboardUrl())
                .build();
    }
}
