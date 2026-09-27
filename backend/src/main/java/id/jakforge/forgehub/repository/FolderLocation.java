package id.jakforge.forgehub.repository;

import java.util.UUID;

/**
 * Satu folder tempat sebuah nama dipakai — proses atau pemicu — dan apakah
 * itu folder bawaan penyewa.
 *
 * <p>Nama proses dan pemicu unik PER FOLDER (V5), jadi satu nama bisa ada di
 * beberapa folder. Permintaan yang hanya menyebut nama diputuskan dari daftar
 * ini oleh {@code FolderLocations.resolve}.
 */
public record FolderLocation(UUID folderId, boolean isDefault) {
}
