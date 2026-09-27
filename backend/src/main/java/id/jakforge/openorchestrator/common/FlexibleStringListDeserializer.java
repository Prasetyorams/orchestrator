package id.jakforge.openorchestrator.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Daftar teks dari larik JSON ATAU satu untai dipisah koma.
 *
 * <p>Untai dipisah koma adalah bentuk yang tersimpan di basis data (mis.
 * {@code roles.permissions}), dan klien lama mengirim bentuk itu apa adanya.
 * Unsur null di dalam larik dibuang; unsur bukan teks diubah menjadi teksnya.
 */
public class FlexibleStringListDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        List<String> result = new ArrayList<>();

        if (parser.currentToken() == JsonToken.START_ARRAY) {
            for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                if (token == JsonToken.VALUE_NULL) continue;

                // Objek atau larik bersarang tidak dibuang diam-diam: teksnya
                // ikut, dan pemeriksa di belakangnya yang menolaknya dengan
                // pesan yang menyebut nilai itu.
                result.add(token.isStructStart() ? context.readTree(parser).toString() : parser.getValueAsString());
            }

            return result;
        }

        result.addAll(List.of(parser.getValueAsString("").split(",")));
        return result;
    }
}
