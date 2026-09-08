package id.jakforge.forgehub.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bentuk balasan kesalahan yang seragam.
 *
 * <p>Selalu {@code {"error": "..."}} dan tidak pernah bentuk lain. Itu bukan
 * pilihan gaya: Studio, JakRunner, dan dasbor semuanya membaca medan
 * {@code error}, jadi balasan kesalahan yang berbeda bentuk akan muncul di sana
 * sebagai "undefined" — kegagalan tanpa keterangan, yang justru lebih buruk
 * daripada kegagalan itu sendiri.
 *
 * <p>Pesan pengecualian TIDAK diteruskan apa adanya untuk kesalahan yang tak
 * terduga: pesan bawaan kerap memuat nama tabel, kolom, dan potongan kueri.
 * Yang seperti itu berguna di catatan server, bukan di layar orang yang mungkin
 * bukan pemilik sistemnya.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException ex) {
        return balas(ex.status(), ex.getMessage());
    }


    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> salah(IllegalArgumentException ex) {
        return balas(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> tidakSah(MethodArgumentNotValidException ex) {
        String rincian = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return balas(HttpStatus.BAD_REQUEST, rincian);
    }

    /** Badan permintaan yang bukan JSON. Itu permintaan yang salah, bukan server yang rusak. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badanRusak(HttpMessageNotReadableException ex) {
        return balas(HttpStatus.BAD_REQUEST, "Badan permintaan bukan JSON yang sah.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> takTerduga(Exception ex) {
        // Dicatat LENGKAP di sini, karena inilah satu-satunya tempat rinciannya
        // masih ada. Yang dikirim keluar tetap kalimat umum.
        log.error("Kesalahan yang tidak tertangani.", ex);

        return balas(HttpStatus.INTERNAL_SERVER_ERROR, "Terjadi kesalahan di server.");
    }

    private ResponseEntity<Map<String, Object>> balas(HttpStatus status, String pesan) {
        return ResponseEntity.status(status)
                .body(Map.of("error", pesan == null ? status.getReasonPhrase() : pesan));
    }
}
