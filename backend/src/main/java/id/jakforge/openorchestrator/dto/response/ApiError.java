package id.jakforge.openorchestrator.dto.response;

/**
 * Bentuk SATU-SATUNYA balasan kesalahan: {@code {"error": "..."}}.
 *
 * <p>Studio, JakRunner, dan dasbor semuanya membaca medan {@code error}; balasan
 * kesalahan yang berbeda bentuk muncul di sana sebagai "undefined".
 */
public record ApiError(String error) {
}
