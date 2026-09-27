package id.jakforge.forgehub.dto.request;

import id.jakforge.forgehub.common.RequestBodies;

import java.util.Map;

/**
 * Membuat atau menyunting folder (POST /api/folders, PUT /api/folders/{id}).
 *
 * <p>Dibaca dari {@code Map}, bukan record bertipe, karena satu hal yang tidak
 * bisa dibedakan record: {@code parentIdProvided} membedakan "parentId tidak
 * disebut" dari "parentId kosong". Yang pertama berarti biarkan foldernya di
 * tempatnya, yang kedua berarti pindahkan ke akar — dan keduanya terbaca null
 * lewat nilainya saja.
 */
public record FolderRequest(String name, String description, String parentId, boolean parentIdProvided) {

    private static final String PARENT_ID_FIELD = "parentId";

    public static FolderRequest fromBody(Map<String, Object> body) {
        return new FolderRequest(
                RequestBodies.trimmedText(body, "name"),
                RequestBodies.text(body, "description"),
                RequestBodies.trimmedText(body, PARENT_ID_FIELD),
                body != null && body.containsKey(PARENT_ID_FIELD));
    }
}
