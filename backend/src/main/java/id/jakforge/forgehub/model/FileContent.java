package id.jakforge.forgehub.model;

/**
 * Isi berkas untuk diunduh: nama, jenis isi, dan bitanya.
 *
 * <p>Bertipe, bukan peta berkunci teks: controller yang membuat balasan unduhan
 * tidak perlu menebak kunci dan melakukan cast.
 */
public record FileContent(String fileName, String contentType, byte[] content) {

    /** Jenis isi untuk berkas yang disimpan atau diunggah tanpa jenis. */
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
}
