package id.jakforge.openorchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dependensi (Id → versi) sebagai teks JSON di basis data: kolom
 * {@code packages.dependencies} dan {@code libraries.dependencies} (V20).
 */
final class Dependencies {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private Dependencies() {
    }

    static String toJson(Map<String, String> dependencies) {
        try {
            return JSON.writeValueAsString(dependencies == null ? Map.of() : dependencies);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dependensi tidak bisa ditulis sebagai JSON.", e);
        }
    }

    /** Teks yang rusak atau bukan objek dibaca sebagai tanpa dependensi. */
    static Map<String, String> fromJson(Object json) {
        if (json == null) return Map.of();

        try {
            Map<String, Object> raw = JSON.readValue(json.toString(), MAP);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((id, version) -> result.put(id, version == null ? "" : version.toString()));
            return Collections.unmodifiableMap(result);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
