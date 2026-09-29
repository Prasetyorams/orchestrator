package id.jakforge.openorchestrator.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Bentuk SATU-SATUNYA balasan kesalahan: {@code {"error": "..."}}.
 *
 * <p>Studio, JakRunner, dan dasbor semuanya membaca medan {@code error}; balasan
 * kesalahan yang berbeda bentuk muncul di sana sebagai "undefined".
 *
 * <p>Medan lainnya hanya ikut kalau terisi — untuk Robot Agent, yang bercabang
 * berdasarkan {@code errorCode}. Galat lama tetap persis {@code {"error": "..."}}.
 */
public record ApiError(
        String error,
        @JsonInclude(JsonInclude.Include.NON_NULL) String errorCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String state,
        @JsonInclude(JsonInclude.Include.NON_NULL) String minAgentVersion) {

    public ApiError(String error) {
        this(error, null, null, null);
    }
}
