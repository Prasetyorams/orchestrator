package id.jakforge.openorchestrator.common;

import id.jakforge.openorchestrator.dto.response.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    static final String INVALID_JSON_MESSAGE = "Badan permintaan bukan JSON yang sah.";
    static final String UNEXPECTED_ERROR_MESSAGE = "Terjadi kesalahan di server.";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        return errorResponse(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Pelanggaran anotasi validasi pada badan permintaan ({@code @Valid}).
     *
     * <p>Yang dikirim hanya pesan pelanggaran PERTAMA menurut urutan medannya
     * di kelas permintaan — bukan gabungan semuanya. Pesannya kalimat utuh yang
     * sama dengan yang dulu dilempar layanan ("Nama peran wajib diisi."), dan
     * dasbor menerjemahkannya lewat teks persisnya; gabungan beberapa kalimat
     * tidak akan pernah cocok dengan kamusnya.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationFailure(MethodArgumentNotValidException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, firstViolationMessage(ex.getBindingResult()));
    }

    /** Badan permintaan yang bukan JSON. Itu permintaan yang salah, bukan server yang rusak. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, INVALID_JSON_MESSAGE);
    }

    /** Parameter jalur atau kueri yang tidak bisa diubah ke tipenya, mis. /api/alerts/abc/read. */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(TypeMismatchException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.getReasonPhrase());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Pengecualian bawaan Spring MVC — jalur yang tidak ada, metode HTTP
        // yang tidak didukung, parameter wajib yang hilang — sudah membawa
        // statusnya sendiri. Menjawabnya 500 mengarahkan orang mencari
        // kerusakan di server untuk permintaan yang sebenarnya salah alamat.
        if (ex instanceof ErrorResponse standard) {
            HttpStatusCode status = standard.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());

            return errorResponse(status, known == null ? null : known.getReasonPhrase());
        }

        // Dicatat LENGKAP di sini, karena inilah satu-satunya tempat rinciannya
        // masih ada. Yang dikirim keluar tetap kalimat umum.
        log.error("Kesalahan yang tidak tertangani.", ex);

        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_MESSAGE);
    }

    private static ResponseEntity<ApiError> errorResponse(HttpStatusCode status, String message) {
        String reason = status instanceof HttpStatus known ? known.getReasonPhrase() : String.valueOf(status.value());

        return ResponseEntity.status(status).body(new ApiError(message == null ? reason : message));
    }

    /** Pesan pelanggaran pada medan yang paling awal dideklarasikan. */
    private static String firstViolationMessage(BindingResult result) {
        List<String> declaredOrder = declaredFieldNames(result.getTarget());

        return result.getFieldErrors().stream()
                .min(Comparator.comparingInt(error -> positionOf(declaredOrder, error)))
                .map(FieldError::getDefaultMessage)
                .or(() -> result.getGlobalErrors().stream().findFirst().map(ObjectError::getDefaultMessage))
                .orElse(HttpStatus.BAD_REQUEST.getReasonPhrase());
    }

    private static List<String> declaredFieldNames(Object target) {
        if (target == null) return List.of();

        Class<?> type = target.getClass();

        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
        }

        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
    }

    private static int positionOf(List<String> declaredOrder, FieldError error) {
        int position = declaredOrder.indexOf(error.getField());
        return position < 0 ? Integer.MAX_VALUE : position;
    }
}
