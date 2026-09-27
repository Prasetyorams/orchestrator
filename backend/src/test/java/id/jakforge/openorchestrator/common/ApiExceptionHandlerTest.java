package id.jakforge.openorchestrator.common;

import id.jakforge.openorchestrator.dto.response.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Semua kesalahan berbentuk {@code {"error": "..."}}, dengan status yang tepat. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    /** Permintaan contoh: urutan medannya menentukan pelanggaran mana yang dilaporkan. */
    record SampleRequest(String name, String description) {
    }

    @SuppressWarnings("unused")
    void sampleEndpoint(SampleRequest request) {
    }

    @Test
    @DisplayName("ApiException membawa status dan pesannya sendiri")
    void apiExceptionKeepsStatusAndMessage() {
        ResponseEntity<ApiError> response = handler.handleApiException(ApiException.conflict("Sudah ada."));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Sudah ada.", response.getBody().error());
    }

    @Test
    @DisplayName("pelanggaran validasi: hanya pesan medan PERTAMA menurut urutan deklarasinya")
    void validationReportsFirstDeclaredField() throws Exception {
        SampleRequest target = new SampleRequest(null, "x".repeat(500));
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "request");

        // Ditambahkan terbalik: urutan pelanggaran dari validator tidak bisa diandalkan.
        bindingResult.addError(new FieldError("request", "description", "Keterangan paling panjang 400 karakter."));
        bindingResult.addError(new FieldError("request", "name", "Nama wajib diisi."));

        MethodParameter parameter = new MethodParameter(
                getClass().getDeclaredMethod("sampleEndpoint", SampleRequest.class), 0);

        ResponseEntity<ApiError> response =
                handler.handleValidationFailure(new MethodArgumentNotValidException(parameter, bindingResult));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Nama wajib diisi.", response.getBody().error());
    }

    @Test
    @DisplayName("pengecualian bawaan Spring MVC memakai statusnya sendiri, bukan 500")
    void standardMvcExceptionsKeepTheirStatus() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(new HttpRequestMethodNotSupportedException("PATCH"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals("Method Not Allowed", response.getBody().error());
    }

    @Test
    @DisplayName("parameter yang salah tipe adalah permintaan yang salah (400)")
    void typeMismatchIsBadRequest() {
        ResponseEntity<ApiError> response = handler.handleTypeMismatch(new TypeMismatchException("abc", Long.class));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("kesalahan tak terduga dijawab 500 dengan kalimat umum, tanpa rincian dari dalam")
    void unexpectedErrorsHideDetails() {
        ResponseEntity<ApiError> response =
                handler.handleUnexpected(new IllegalStateException("relation \"jobs\" does not exist"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(ApiExceptionHandler.UNEXPECTED_ERROR_MESSAGE, response.getBody().error());
    }
}
